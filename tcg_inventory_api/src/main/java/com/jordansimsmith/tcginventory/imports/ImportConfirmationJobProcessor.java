package com.jordansimsmith.tcginventory.imports;

import com.jordansimsmith.tcginventory.CardIdentity;
import com.jordansimsmith.tcginventory.Condition;
import com.jordansimsmith.tcginventory.JobItem;
import com.jordansimsmith.tcginventory.JobProcessor;
import com.jordansimsmith.tcginventory.SkuIds;
import com.jordansimsmith.tcginventory.TcgInventoryFactory;
import com.jordansimsmith.tcginventory.TcgInventoryTable;
import com.jordansimsmith.tcginventory.inventory.InventoryRepository;
import com.jordansimsmith.tcginventory.inventory.SkuItem;
import com.jordansimsmith.tcginventory.inventory.UnitItem;
import com.jordansimsmith.time.Clock;
import java.util.HashMap;
import java.util.List;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryEnhancedRequest;

public class ImportConfirmationJobProcessor implements JobProcessor {
  private final Clock clock;
  private final DynamoDbTable<ImportItem> importTable;
  private final DynamoDbTable<ImportRowItem> importRowTable;
  private final ImportsRepository importsRepository;
  private final InventoryRepository inventoryRepository;

  public ImportConfirmationJobProcessor(TcgInventoryFactory factory) {
    this.clock = factory.clock();
    this.importTable = TcgInventoryTable.table(factory.dynamoDbEnhancedClient(), ImportItem.class);
    this.importRowTable =
        TcgInventoryTable.table(factory.dynamoDbEnhancedClient(), ImportRowItem.class);
    this.importsRepository =
        new ImportsRepository(factory.dynamoDbClient(), importTable, factory.jobTable());
    this.inventoryRepository =
        new InventoryRepository(
            TcgInventoryTable.table(factory.dynamoDbEnhancedClient(), UnitItem.class),
            factory.dynamoDbClient(),
            factory.clock(),
            factory.ulidGenerator());
  }

  @Override
  public JobProcessor.JobResult processBatch(String user, JobItem jobItem) {
    var importId = jobItem.getImportId();
    if (importId == null) {
      throw new IllegalStateException("import confirmation job is missing required identity");
    }

    var importKey =
        Key.builder()
            .partitionValue(ImportItem.formatPk(user))
            .sortValue(ImportItem.formatSk(importId))
            .build();
    var importItem = importTable.getItem(request -> request.key(importKey).consistentRead(true));
    if (importItem == null) {
      throw new IllegalStateException("import not found for confirmation job");
    }
    if ("confirmed".equals(importItem.getStatus())) {
      return new JobProcessor.SuccessJobResult(0, true);
    }
    if (!"confirming".equals(importItem.getStatus())) {
      throw new IllegalStateException("import is not in confirming status");
    }

    var keepRows = queryKeepRows(user, importId);
    if (!keepRows.isEmpty()) {
      var firstSequenceNumber =
          allocateSequenceRange(user, importItem.getGame(), keepRows.size(), keepRows);
      assignSequenceNumbers(keepRows, firstSequenceNumber);

      var skuSeeds = new HashMap<String, SkuItem>();
      for (var row : keepRows) {
        var identity = new CardIdentity(importItem.getGame(), row.getExternalId());
        var skuId = SkuIds.format(identity, row.getFinish(), Condition.valueOf(row.getCondition()));
        var skuSeed =
            skuSeeds.computeIfAbsent(
                skuId,
                ignored -> {
                  var seed =
                      SkuItem.create(
                          user,
                          skuId,
                          importItem.getGame(),
                          row.getExternalId(),
                          row.getFinish(),
                          row.getCondition(),
                          row.getName(),
                          row.getSetCode(),
                          row.getSetName(),
                          row.getCollectorNumber(),
                          row.getFetchtcgCardId(),
                          row.getSuggestedPrice());
                  seed.setFetchtcgSetId(row.getFetchtcgSetId());
                  return seed;
                });

        var unit =
            UnitItem.create(
                user,
                importItem.getGame(),
                skuId,
                row.getSequenceNumber(),
                "in_stock",
                importId,
                clock.now());
        if (row.getPhotos() != null && !row.getPhotos().isEmpty()) {
          unit.setPhotos(
              row.getPhotos().stream()
                  .map(photo -> UnitItem.Photo.create(photo.getPhotoId(), photo.getFetchtcgUrl()))
                  .toList());
        }
        inventoryRepository.confirmImportUnit(user, importId, skuSeed, unit);
      }
    }

    importsRepository.finishConfirmation(user, importId, clock.now());
    return new JobProcessor.SuccessJobResult(keepRows.size(), true);
  }

  private List<ImportRowItem> queryKeepRows(String user, String importId) {
    return importRowTable
        .query(
            QueryEnhancedRequest.builder()
                .queryConditional(
                    QueryConditional.sortBeginsWith(
                        Key.builder()
                            .partitionValue(ImportRowItem.formatPk(user, importId))
                            .sortValue(ImportRowItem.ROW_PREFIX)
                            .build()))
                .scanIndexForward(true)
                .consistentRead(true)
                .build())
        .stream()
        .flatMap(page -> page.items().stream())
        .filter(row -> "keep".equals(row.getDecision()))
        .toList();
  }

  private int allocateSequenceRange(
      String user, String game, int keepCount, List<ImportRowItem> keepRows) {
    var firstRowWithSequenceNumber =
        keepRows.stream().filter(row -> row.getSequenceNumber() != null).findFirst().orElse(null);
    if (firstRowWithSequenceNumber != null) {
      return keepRows.stream()
          .filter(row -> row.getSequenceNumber() != null)
          .mapToInt(ImportRowItem::getSequenceNumber)
          .min()
          .orElse(0);
    }
    return inventoryRepository.allocateSequenceRange(user, game, keepCount);
  }

  private void assignSequenceNumbers(List<ImportRowItem> keepRows, int firstSequenceNumber) {
    var sequenceNumber = firstSequenceNumber;
    for (var row : keepRows) {
      if (row.getSequenceNumber() == null) {
        row.setSequenceNumber(sequenceNumber);
        importRowTable.putItem(row);
      }
      sequenceNumber++;
    }
  }
}
