package com.jordansimsmith.tcginventory.imports;

import com.google.common.collect.Lists;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryEnhancedRequest;

public class ImportConfirmationJobProcessor implements JobProcessor {
  private final Clock clock;
  private final DynamoDbTable<ImportItem> importTable;
  private final DynamoDbTable<ImportRowItem> importRowTable;
  private final InventoryRepository inventoryRepository;

  public ImportConfirmationJobProcessor(TcgInventoryFactory factory) {
    this.clock = factory.clock();
    this.importTable = TcgInventoryTable.table(factory.dynamoDbEnhancedClient(), ImportItem.class);
    this.importRowTable =
        TcgInventoryTable.table(factory.dynamoDbEnhancedClient(), ImportRowItem.class);
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

    var importItem =
        importTable.getItem(
            Key.builder()
                .partitionValue(ImportItem.formatPk(user))
                .sortValue(ImportItem.formatSk(importId))
                .build());
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

      for (var entry : groupBySkuId(importItem.getGame(), keepRows).entrySet()) {
        var chunks =
            Lists.partition(entry.getValue(), ImportsRepository.MAX_IMPORT_UNITS_PER_TRANSACTION);
        for (var chunk : chunks) {
          confirmSkuChunk(user, importItem.getGame(), importId, entry.getKey(), chunk);
        }
      }
    }

    importItem.setStatus("confirmed");
    importItem.setUpdatedAt(clock.now());
    importTable.putItem(importItem);
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

  private Map<String, List<ImportRowItem>> groupBySkuId(String game, List<ImportRowItem> keepRows) {
    var groups = new HashMap<String, List<ImportRowItem>>();
    for (var row : keepRows) {
      var identity = new CardIdentity(game, row.getExternalSource(), row.getExternalId());
      var skuId = SkuIds.format(identity, row.getFinish(), Condition.valueOf(row.getCondition()));
      groups.computeIfAbsent(skuId, ignored -> new ArrayList<>()).add(row);
    }
    return groups;
  }

  private void confirmSkuChunk(
      String user, String game, String importId, String skuId, List<ImportRowItem> rows) {
    var firstRow = rows.get(0);
    var skuSeed =
        SkuItem.create(
            user,
            skuId,
            game,
            firstRow.getExternalSource(),
            firstRow.getExternalId(),
            firstRow.getFinish(),
            firstRow.getCondition(),
            firstRow.getName(),
            firstRow.getSetCode(),
            firstRow.getSetName(),
            firstRow.getCollectorNumber(),
            firstRow.getFetchtcgCardId(),
            firstRow.getSuggestedPrice());
    skuSeed.setFetchtcgSetId(firstRow.getFetchtcgSetId());

    var units = new ArrayList<UnitItem>();
    for (var row : rows) {
      var unit =
          UnitItem.create(
              user, game, skuId, row.getSequenceNumber(), "in_stock", importId, clock.now());
      if (row.getPhotos() != null && !row.getPhotos().isEmpty()) {
        unit.setPhotos(
            row.getPhotos().stream()
                .map(photo -> UnitItem.Photo.create(photo.getPhotoId(), photo.getFetchtcgUrl()))
                .toList());
      }
      units.add(unit);
    }

    inventoryRepository.confirmImportSku(user, importId, skuSeed, units);
  }
}
