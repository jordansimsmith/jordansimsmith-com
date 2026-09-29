package com.jordansimsmith.tcginventory.imports;

import com.jordansimsmith.tcginventory.JobItem;
import com.jordansimsmith.tcginventory.TcgInventoryTable;
import com.jordansimsmith.tcginventory.inventory.InventoryRepository;
import com.jordansimsmith.tcginventory.inventory.SkuItem;
import com.jordansimsmith.tcginventory.inventory.UnitItem;
import com.jordansimsmith.time.Clock;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryEnhancedRequest;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionCheck;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.Put;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;
import software.amazon.awssdk.services.dynamodb.model.Update;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;

public class ImportRepository {
  private final DynamoDbTable<ImportItem> importTable;
  private final DynamoDbTable<ImportRowItem> importRowTable;
  private final DynamoDbTable<JobItem> jobTable;
  private final InventoryRepository inventoryRepository;
  private final DynamoDbClient dynamoDbClient;
  private final Clock clock;

  public ImportRepository(
      DynamoDbTable<ImportItem> importTable,
      DynamoDbTable<ImportRowItem> importRowTable,
      DynamoDbTable<JobItem> jobTable,
      InventoryRepository inventoryRepository,
      DynamoDbClient dynamoDbClient,
      Clock clock) {
    this.importTable = importTable;
    this.importRowTable = importRowTable;
    this.jobTable = jobTable;
    this.inventoryRepository = inventoryRepository;
    this.dynamoDbClient = dynamoDbClient;
    this.clock = clock;
  }

  @Nullable
  public ImportItem getImport(String user, String importId) {
    var response =
        dynamoDbClient.getItem(
            GetItemRequest.builder()
                .tableName(TcgInventoryTable.TABLE_NAME)
                .key(importKey(user, importId))
                .consistentRead(true)
                .build());
    return response.hasItem() ? importTable.tableSchema().mapToItem(response.item()) : null;
  }

  public List<ImportRowItem> findKeepRows(String user, String importId) {
    return findRows(user, importId).stream()
        .filter(row -> "keep".equals(row.getDecision()))
        .toList();
  }

  public List<ImportRowItem> findRows(String user, String importId) {
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
        .toList();
  }

  @Nullable
  public String getConfirmationError(String user, ImportItem importItem) {
    if (!"confirming".equals(importItem.getStatus())) {
      return null;
    }
    var jobId = JobItem.formatResourceJobId("confirm_import", importItem.getImportId());
    var job = getJob(user, jobId);
    if (job == null) {
      throw new IllegalStateException("confirmation job is missing: " + jobId);
    }
    verifyConfirmationJob(job, importItem.getImportId());
    return job.getError();
  }

  @Nullable
  public JobItem ensureConfirmationJob(String user, String importId) {
    var jobId = JobItem.formatResourceJobId("confirm_import", importId);
    for (int attempt = 0; attempt < 3; attempt++) {
      var current = getImport(user, importId);
      if (current == null) {
        throw new IllegalStateException("import not found: " + importId);
      }
      if ("confirmed".equals(current.getStatus())) {
        return null;
      }
      if (!"review".equals(current.getStatus()) && !"confirming".equals(current.getStatus())) {
        throw new IllegalStateException("import is not available for confirmation");
      }

      var job = getJob(user, jobId);
      if (job != null) {
        verifyConfirmationJob(job, importId);
        if ("failed".equals(job.getStatus())) {
          var retryJob = resetFailedJob(user, job);
          if ("queued".equals(retryJob.getStatus()) || "running".equals(retryJob.getStatus())) {
            return retryJob;
          }
          if ("succeeded".equals(retryJob.getStatus())) {
            var latest = getImport(user, importId);
            if (latest != null && "confirmed".equals(latest.getStatus())) {
              return null;
            }
          }
          if (attempt < 2) {
            continue;
          }
          throw new IllegalStateException("confirmation retry job is not active");
        }
        if ("succeeded".equals(job.getStatus())) {
          var latest = getImport(user, importId);
          if (latest != null && "confirmed".equals(latest.getStatus())) {
            return null;
          }
          if (attempt < 2) {
            continue;
          }
          throw new IllegalStateException("confirmation job succeeded while import is confirming");
        }
        return job;
      }

      var newJob = JobItem.create(user, jobId, "confirm_import", importId, clock.now());
      var jobPut = buildConfirmationJobPut(newJob);
      var parentTransition =
          "review".equals(current.getStatus())
              ? TransactWriteItem.builder()
                  .update(
                      Update.builder()
                          .tableName(TcgInventoryTable.TABLE_NAME)
                          .key(importKey(user, importId))
                          .updateExpression(
                              "SET #status = :confirming, " + ImportItem.UPDATED_AT + " = :now")
                          .conditionExpression("#status = :review")
                          .expressionAttributeNames(Map.of("#status", ImportItem.STATUS))
                          .expressionAttributeValues(
                              Map.of(
                                  ":review", s("review"),
                                  ":confirming", s("confirming"),
                                  ":now", n(clock.now().getEpochSecond())))
                          .build())
                  .build()
              : TransactWriteItem.builder()
                  .conditionCheck(
                      ConditionCheck.builder()
                          .tableName(TcgInventoryTable.TABLE_NAME)
                          .key(importKey(user, importId))
                          .conditionExpression("#status = :confirming")
                          .expressionAttributeNames(Map.of("#status", ImportItem.STATUS))
                          .expressionAttributeValues(Map.of(":confirming", s("confirming")))
                          .build())
                  .build();
      try {
        dynamoDbClient.transactWriteItems(
            TransactWriteItemsRequest.builder()
                .transactItems(List.of(parentTransition, jobPut))
                .build());
        return newJob;
      } catch (TransactionCanceledException e) {
        var latest = getImport(user, importId);
        if (latest != null && "confirmed".equals(latest.getStatus())) {
          return null;
        }
        if (attempt == 2) {
          throw e;
        }
      }
    }
    throw new IllegalStateException("could not create import confirmation job");
  }

  private TransactWriteItem buildConfirmationJobPut(JobItem job) {
    return TransactWriteItem.builder()
        .put(
            Put.builder()
                .tableName(TcgInventoryTable.TABLE_NAME)
                .item(jobTable.tableSchema().itemToMap(job, true))
                .conditionExpression("attribute_not_exists(" + JobItem.PK + ")")
                .build())
        .build();
  }

  private void verifyConfirmationJob(JobItem job, String importId) {
    if (!"confirm_import".equals(job.getJobType()) || !importId.equals(job.getImportId())) {
      throw new IllegalStateException("confirmation job does not match import " + importId);
    }
  }

  private JobItem resetFailedJob(String user, JobItem job) {
    var jobId = job.getJobId();
    var nextContinuation = job.getContinuation() == null ? 1 : job.getContinuation() + 1;
    try {
      dynamoDbClient.updateItem(
          UpdateItemRequest.builder()
              .tableName(TcgInventoryTable.TABLE_NAME)
              .key(jobKey(user, jobId))
              .updateExpression(
                  "SET #status = :queued, "
                      + JobItem.UPDATED_AT
                      + " = :now, "
                      + JobItem.PROCESSED_COUNT
                      + " = :zero, "
                      + JobItem.CONTINUATION
                      + " = :continuation REMOVE #error")
              .conditionExpression("#status = :failed")
              .expressionAttributeNames(Map.of("#status", JobItem.STATUS, "#error", JobItem.ERROR))
              .expressionAttributeValues(
                  Map.of(
                      ":queued", s("queued"),
                      ":failed", s("failed"),
                      ":zero", n(0),
                      ":continuation", n(nextContinuation),
                      ":now", n(clock.now().getEpochSecond())))
              .build());
    } catch (ConditionalCheckFailedException e) {
      var current = getJob(user, jobId);
      if (current == null) {
        throw e;
      }
      return current;
    }
    var current = getJob(user, jobId);
    if (current == null) {
      throw new IllegalStateException("confirmation job is missing: " + jobId);
    }
    return current;
  }

  public int freezeSequenceRange(String user, ImportItem importItem, List<ImportRowItem> keepRows) {
    if (importItem.getConfirmationUnitCount() != null
        && importItem.getFirstSequenceNumber() != null) {
      if (importItem.getConfirmationUnitCount() != keepRows.size()) {
        throw new IllegalStateException("import confirmation row count changed");
      }
      return importItem.getFirstSequenceNumber();
    }

    int firstSequenceNumber;
    var existingRows = keepRows.stream().filter(row -> row.getSequenceNumber() != null).toList();
    if (existingRows.isEmpty()) {
      firstSequenceNumber =
          keepRows.isEmpty()
              ? 0
              : inventoryRepository.allocateSequenceRange(
                  user, importItem.getGame(), keepRows.size());
    } else {
      var firstAssignedIndex = keepRows.indexOf(existingRows.get(0));
      firstSequenceNumber = existingRows.get(0).getSequenceNumber() - firstAssignedIndex;
      for (int index = 0; index < keepRows.size(); index++) {
        var row = keepRows.get(index);
        if (row.getSequenceNumber() != null
            && row.getSequenceNumber() != firstSequenceNumber + index) {
          throw new IllegalStateException("legacy import sequence range is ambiguous");
        }
      }
    }

    try {
      dynamoDbClient.updateItem(
          UpdateItemRequest.builder()
              .tableName(TcgInventoryTable.TABLE_NAME)
              .key(importKey(user, importItem.getImportId()))
              .updateExpression(
                  "SET "
                      + ImportItem.FIRST_SEQUENCE_NUMBER
                      + " = :first, "
                      + ImportItem.CONFIRMATION_UNIT_COUNT
                      + " = :count, "
                      + ImportItem.UPDATED_AT
                      + " = :now")
              .conditionExpression(
                  "#status = :confirming AND attribute_not_exists("
                      + ImportItem.FIRST_SEQUENCE_NUMBER
                      + ")")
              .expressionAttributeNames(Map.of("#status", ImportItem.STATUS))
              .expressionAttributeValues(
                  Map.of(
                      ":confirming", s("confirming"),
                      ":first", n(firstSequenceNumber),
                      ":count", n(keepRows.size()),
                      ":now", n(clock.now().getEpochSecond())))
              .build());
      return firstSequenceNumber;
    } catch (ConditionalCheckFailedException e) {
      var current = getImport(user, importItem.getImportId());
      if (current == null
          || current.getFirstSequenceNumber() == null
          || current.getConfirmationUnitCount() == null
          || current.getConfirmationUnitCount() != keepRows.size()) {
        throw e;
      }
      return current.getFirstSequenceNumber();
    }
  }

  public void confirmRow(
      String user,
      ImportItem importItem,
      ImportRowItem row,
      SkuItem sku,
      UnitItem unit,
      int sequenceNumber) {
    var parentCheck = buildImportCheck(user, importItem.getImportId());
    var rowCompletion = buildRowCompletion(row, sequenceNumber);
    try {
      inventoryRepository.confirmImportUnit(
          user, importItem.getImportId(), unit, sku, parentCheck, rowCompletion);
    } catch (TransactionCanceledException e) {
      var currentRow = getRow(user, importItem.getImportId(), row.getPosition());
      if (Boolean.TRUE.equals(currentRow.getConfirmed())) {
        if (!Integer.valueOf(sequenceNumber).equals(currentRow.getSequenceNumber())) {
          throw new IllegalStateException("confirmed import row has an unexpected sequence number");
        }
        return;
      }
      var existingUnit =
          inventoryRepository.getUnitConsistent(user, sku.getSkuId(), sequenceNumber);
      if (existingUnit == null
          || !importItem.getImportId().equals(existingUnit.getImportId())
          || !"in_stock".equals(existingUnit.getStatus())) {
        throw new IllegalStateException(
            "import unit conflicts with existing inventory at sequence " + sequenceNumber, e);
      }
      inventoryRepository.completeLegacyImportUnit(
          user,
          importItem.getImportId(),
          sku.getSkuId(),
          sequenceNumber,
          parentCheck,
          buildRowCompletion(currentRow, sequenceNumber));
    }
  }

  @Nullable
  public ImportRowItem getRow(String user, String importId, Integer position) {
    if (position == null) {
      throw new IllegalStateException("import row is missing its position");
    }
    var response =
        dynamoDbClient.getItem(
            GetItemRequest.builder()
                .tableName(TcgInventoryTable.TABLE_NAME)
                .key(
                    Map.of(
                        ImportRowItem.PK, s(ImportRowItem.formatPk(user, importId)),
                        ImportRowItem.SK, s(ImportRowItem.formatSk(position))))
                .consistentRead(true)
                .build());
    return response.hasItem() ? importRowTable.tableSchema().mapToItem(response.item()) : null;
  }

  public void finishConfirmation(String user, String importId, int unitCount) {
    var update =
        TransactWriteItem.builder()
            .update(
                Update.builder()
                    .tableName(TcgInventoryTable.TABLE_NAME)
                    .key(importKey(user, importId))
                    .updateExpression(
                        "SET #status = :confirmed, "
                            + ImportItem.UPDATED_AT
                            + " = :now REMOVE confirmation_error")
                    .conditionExpression("#status = :confirming")
                    .expressionAttributeNames(Map.of("#status", ImportItem.STATUS))
                    .expressionAttributeValues(
                        Map.of(
                            ":confirmed", s("confirmed"),
                            ":confirming", s("confirming"),
                            ":now", n(clock.now().getEpochSecond())))
                    .build())
            .build();
    var audit =
        inventoryRepository.buildAuditPut(
            user, "import_confirm", Map.of("import_id", s(importId), "unit_count", n(unitCount)));
    try {
      dynamoDbClient.transactWriteItems(
          TransactWriteItemsRequest.builder().transactItems(List.of(update, audit)).build());
    } catch (TransactionCanceledException e) {
      var current = getImport(user, importId);
      if (current == null || !"confirmed".equals(current.getStatus())) {
        throw e;
      }
    }
  }

  private TransactWriteItem buildImportCheck(String user, String importId) {
    return TransactWriteItem.builder()
        .conditionCheck(
            ConditionCheck.builder()
                .tableName(TcgInventoryTable.TABLE_NAME)
                .key(importKey(user, importId))
                .conditionExpression("#status = :confirming")
                .expressionAttributeNames(Map.of("#status", ImportItem.STATUS))
                .expressionAttributeValues(Map.of(":confirming", s("confirming")))
                .build())
        .build();
  }

  private TransactWriteItem buildRowCompletion(ImportRowItem row, int sequenceNumber) {
    return TransactWriteItem.builder()
        .update(
            Update.builder()
                .tableName(TcgInventoryTable.TABLE_NAME)
                .key(Map.of(ImportRowItem.PK, s(row.getPk()), ImportRowItem.SK, s(row.getSk())))
                .updateExpression(
                    "SET "
                        + ImportRowItem.CONFIRMED
                        + " = :true, "
                        + ImportRowItem.SEQUENCE_NUMBER
                        + " = :sequence")
                .conditionExpression(
                    "attribute_not_exists("
                        + ImportRowItem.CONFIRMED
                        + ") OR "
                        + ImportRowItem.CONFIRMED
                        + " = :false")
                .expressionAttributeValues(
                    Map.of(
                        ":true", AttributeValue.builder().bool(true).build(),
                        ":false", AttributeValue.builder().bool(false).build(),
                        ":sequence", n(sequenceNumber)))
                .build())
        .build();
  }

  private JobItem getJob(String user, String jobId) {
    var response =
        dynamoDbClient.getItem(
            GetItemRequest.builder()
                .tableName(TcgInventoryTable.TABLE_NAME)
                .key(jobKey(user, jobId))
                .consistentRead(true)
                .build());
    return response.hasItem() ? jobTable.tableSchema().mapToItem(response.item()) : null;
  }

  private static Map<String, AttributeValue> importKey(String user, String importId) {
    return Map.of(
        ImportItem.PK, s(ImportItem.formatPk(user)),
        ImportItem.SK, s(ImportItem.formatSk(importId)));
  }

  private static Map<String, AttributeValue> jobKey(String user, String jobId) {
    return Map.of(JobItem.PK, s(JobItem.formatPk(user)), JobItem.SK, s(JobItem.formatSk(jobId)));
  }

  private static AttributeValue s(String value) {
    return AttributeValue.builder().s(value).build();
  }

  private static AttributeValue n(long value) {
    return AttributeValue.builder().n(String.valueOf(value)).build();
  }
}
