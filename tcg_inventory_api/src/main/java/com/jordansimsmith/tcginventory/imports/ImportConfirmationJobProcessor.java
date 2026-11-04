package com.jordansimsmith.tcginventory.imports;

import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.service.ServiceException;
import com.jordansimsmith.tcginventory.JobItem;
import com.jordansimsmith.tcginventory.JobProcessor;
import com.jordansimsmith.tcginventory.inventory.client.Condition;
import com.jordansimsmith.tcginventory.inventory.client.CreateUnitRequest;
import com.jordansimsmith.tcginventory.inventory.client.InventoryService;
import com.jordansimsmith.time.Clock;
import java.math.BigDecimal;
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
  private final InventoryService inventoryService;

  public ImportConfirmationJobProcessor(
      Clock clock,
      DynamoDbTable<ImportItem> importTable,
      DynamoDbTable<ImportRowItem> importRowTable,
      ImportsRepository importsRepository,
      InventoryService inventoryService) {
    this.clock = clock;
    this.importTable = importTable;
    this.importRowTable = importRowTable;
    this.importsRepository = importsRepository;
    this.inventoryService = inventoryService;
  }

  @Override
  public JobProcessor.JobResult processBatch(String user, JobItem jobItem) throws ServiceException {
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
      return new JobProcessor.SuccessJobResult(0, null);
    }
    if (!"confirming".equals(importItem.getStatus())) {
      throw new IllegalStateException("import is not in confirming status");
    }

    var keepRows = queryKeepRows(user, importId);
    var ctx = new RequestContext(user);
    for (var row : keepRows) {
      if (row.getSkuId() != null) {
        continue;
      }

      var created =
          inventoryService.createUnit(
              ctx,
              new CreateUnitRequest(
                  "import:" + importId + ":row:" + row.getPosition(),
                  importItem.getGame(),
                  row.getExternalId(),
                  row.getFinish(),
                  Condition.valueOf(row.getCondition()),
                  row.getName(),
                  row.getSetCode(),
                  row.getSetName(),
                  row.getCollectorNumber(),
                  row.getSuggestedPrice() == null ? null : new BigDecimal(row.getSuggestedPrice()),
                  row.getFetchtcgCardId(),
                  row.getFetchtcgSetId(),
                  row.getPhotos() == null
                      ? List.of()
                      : row.getPhotos().stream().map(ImportRowItem.Photo::getPhotoId).toList()));
      importsRepository.recordCreatedUnit(
          user,
          importId,
          row.getPosition(),
          created.skuId(),
          created.sequenceNumber(),
          created.location());
    }

    importsRepository.finishConfirmation(user, importId, clock.now());
    return new JobProcessor.SuccessJobResult(keepRows.size(), null);
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
}
