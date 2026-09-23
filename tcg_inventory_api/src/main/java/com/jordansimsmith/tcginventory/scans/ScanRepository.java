package com.jordansimsmith.tcginventory.scans;

import com.jordansimsmith.tcginventory.TcgInventoryTable;
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
import software.amazon.awssdk.services.dynamodb.model.Delete;
import software.amazon.awssdk.services.dynamodb.model.DeleteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.Put;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;

public class ScanRepository {
  private static final int MAX_TRANSACT_ITEMS = 100;

  public record ScanPage(List<ScanItem> items, Map<String, AttributeValue> lastEvaluatedKey) {}

  private final DynamoDbTable<ScanItem> scanTable;
  private final DynamoDbTable<ScanRowItem> scanRowTable;
  private final DynamoDbClient dynamoDbClient;
  private final Clock clock;

  public ScanRepository(
      DynamoDbTable<ScanItem> scanTable,
      DynamoDbTable<ScanRowItem> scanRowTable,
      DynamoDbClient dynamoDbClient,
      Clock clock) {
    this.scanTable = scanTable;
    this.scanRowTable = scanRowTable;
    this.dynamoDbClient = dynamoDbClient;
    this.clock = clock;
  }

  public void createScan(ScanItem scanItem, List<ScanRowItem> rowItems) {
    for (int start = 0; start < rowItems.size(); start += MAX_TRANSACT_ITEMS) {
      var end = Math.min(start + MAX_TRANSACT_ITEMS, rowItems.size());
      var writes = rowItems.subList(start, end).stream().map(this::buildScanRowPut).toList();
      dynamoDbClient.transactWriteItems(
          TransactWriteItemsRequest.builder().transactItems(writes).build());
    }

    dynamoDbClient.transactWriteItems(
        TransactWriteItemsRequest.builder().transactItems(List.of(buildScanPut(scanItem))).build());
  }

  public ScanPage findScans(
      String user, int limit, @Nullable Map<String, AttributeValue> exclusiveStartKey) {
    var requestBuilder =
        QueryEnhancedRequest.builder()
            .queryConditional(
                QueryConditional.sortBeginsWith(
                    Key.builder()
                        .partitionValue(ScanItem.formatPk(user))
                        .sortValue(ScanItem.SCAN_PREFIX)
                        .build()))
            .scanIndexForward(false)
            .limit(limit);
    if (exclusiveStartKey != null && !exclusiveStartKey.isEmpty()) {
      requestBuilder.exclusiveStartKey(exclusiveStartKey);
    }

    var page = scanTable.query(requestBuilder.build()).stream().findFirst().orElse(null);
    if (page == null) {
      return new ScanPage(List.of(), Map.of());
    }
    return new ScanPage(page.items(), page.lastEvaluatedKey());
  }

  public boolean transitionScanToIdentifying(String user, String scanId) {
    try {
      dynamoDbClient.updateItem(
          UpdateItemRequest.builder()
              .tableName(TcgInventoryTable.TABLE_NAME)
              .key(
                  Map.of(
                      ScanItem.PK,
                      AttributeValue.builder().s(ScanItem.formatPk(user)).build(),
                      ScanItem.SK,
                      AttributeValue.builder().s(ScanItem.formatSk(scanId)).build()))
              .updateExpression("SET #status = :identifying, updated_at = :now")
              .conditionExpression("attribute_exists(" + ScanItem.PK + ") AND #status = :uploading")
              .expressionAttributeNames(Map.of("#status", ScanItem.STATUS))
              .expressionAttributeValues(
                  Map.of(
                      ":identifying", AttributeValue.builder().s("identifying").build(),
                      ":uploading", AttributeValue.builder().s("uploading").build(),
                      ":now",
                          AttributeValue.builder()
                              .n(String.valueOf(clock.now().getEpochSecond()))
                              .build()))
              .build());
      return true;
    } catch (ConditionalCheckFailedException e) {
      return false;
    }
  }

  public boolean transitionScanToConfirmed(String user, String scanId, String importId) {
    try {
      dynamoDbClient.updateItem(
          UpdateItemRequest.builder()
              .tableName(TcgInventoryTable.TABLE_NAME)
              .key(
                  Map.of(
                      ScanItem.PK,
                      AttributeValue.builder().s(ScanItem.formatPk(user)).build(),
                      ScanItem.SK,
                      AttributeValue.builder().s(ScanItem.formatSk(scanId)).build()))
              .updateExpression(
                  "SET #status = :confirmed, "
                      + ScanItem.IMPORT_ID
                      + " = :importId, updated_at = :now")
              .conditionExpression("attribute_exists(" + ScanItem.PK + ") AND #status = :reviewing")
              .expressionAttributeNames(Map.of("#status", ScanItem.STATUS))
              .expressionAttributeValues(
                  Map.of(
                      ":confirmed", AttributeValue.builder().s("confirmed").build(),
                      ":reviewing", AttributeValue.builder().s("reviewing").build(),
                      ":importId", AttributeValue.builder().s(importId).build(),
                      ":now",
                          AttributeValue.builder()
                              .n(String.valueOf(clock.now().getEpochSecond()))
                              .build()))
              .build());
      return true;
    } catch (ConditionalCheckFailedException e) {
      return false;
    }
  }

  public boolean deleteScanRow(String user, String scanId, int scanPosition) {
    var scanKey =
        Map.of(
            ScanItem.PK,
            AttributeValue.builder().s(ScanItem.formatPk(user)).build(),
            ScanItem.SK,
            AttributeValue.builder().s(ScanItem.formatSk(scanId)).build());
    var rowKey =
        Map.of(
            ScanRowItem.PK,
            AttributeValue.builder().s(ScanRowItem.formatPk(user, scanId)).build(),
            ScanRowItem.SK,
            AttributeValue.builder().s(ScanRowItem.formatSk(scanPosition)).build());

    try {
      dynamoDbClient.transactWriteItems(
          TransactWriteItemsRequest.builder()
              .transactItems(
                  List.of(
                      TransactWriteItem.builder()
                          .conditionCheck(
                              ConditionCheck.builder()
                                  .tableName(TcgInventoryTable.TABLE_NAME)
                                  .key(scanKey)
                                  .conditionExpression(
                                      "attribute_exists("
                                          + ScanItem.PK
                                          + ") AND #status = :reviewing")
                                  .expressionAttributeNames(Map.of("#status", ScanItem.STATUS))
                                  .expressionAttributeValues(
                                      Map.of(
                                          ":reviewing",
                                          AttributeValue.builder().s("reviewing").build()))
                                  .build())
                          .build(),
                      TransactWriteItem.builder()
                          .delete(
                              Delete.builder()
                                  .tableName(TcgInventoryTable.TABLE_NAME)
                                  .key(rowKey)
                                  .conditionExpression("attribute_exists(" + ScanRowItem.PK + ")")
                                  .build())
                          .build()))
              .build());
      return true;
    } catch (TransactionCanceledException e) {
      return false;
    }
  }

  public boolean deleteScan(String user, String scanId) {
    try {
      dynamoDbClient.deleteItem(
          DeleteItemRequest.builder()
              .tableName(TcgInventoryTable.TABLE_NAME)
              .key(
                  Map.of(
                      ScanItem.PK,
                      AttributeValue.builder().s(ScanItem.formatPk(user)).build(),
                      ScanItem.SK,
                      AttributeValue.builder().s(ScanItem.formatSk(scanId)).build()))
              .conditionExpression(
                  "attribute_exists("
                      + ScanItem.PK
                      + ") AND #status IN (:uploading, :identifying, :reviewing)")
              .expressionAttributeNames(Map.of("#status", ScanItem.STATUS))
              .expressionAttributeValues(
                  Map.of(
                      ":uploading", AttributeValue.builder().s("uploading").build(),
                      ":identifying", AttributeValue.builder().s("identifying").build(),
                      ":reviewing", AttributeValue.builder().s("reviewing").build()))
              .build());
      return true;
    } catch (ConditionalCheckFailedException e) {
      return false;
    }
  }

  public void deleteScanRows(List<ScanRowItem> rowItems) {
    executeChunked(rowItems.stream().map(this::buildScanRowDelete).toList());
  }

  public List<ScanRowItem> findScanRows(String user, String scanId) {
    var request =
        QueryEnhancedRequest.builder()
            .queryConditional(
                QueryConditional.sortBeginsWith(
                    Key.builder()
                        .partitionValue(ScanRowItem.formatPk(user, scanId))
                        .sortValue(ScanRowItem.ROW_PREFIX)
                        .build()))
            .scanIndexForward(true)
            .build();
    return scanRowTable.query(request).stream().flatMap(page -> page.items().stream()).toList();
  }

  @Nullable
  public ScanRowItem getScanRow(String user, String scanId, int scanPosition) {
    return scanRowTable.getItem(
        Key.builder()
            .partitionValue(ScanRowItem.formatPk(user, scanId))
            .sortValue(ScanRowItem.formatSk(scanPosition))
            .build());
  }

  @Nullable
  public ScanItem getScan(String user, String scanId) {
    return scanTable.getItem(
        Key.builder()
            .partitionValue(ScanItem.formatPk(user))
            .sortValue(ScanItem.formatSk(scanId))
            .build());
  }

  private TransactWriteItem buildScanPut(ScanItem item) {
    return TransactWriteItem.builder()
        .put(
            Put.builder()
                .tableName(TcgInventoryTable.TABLE_NAME)
                .item(scanTable.tableSchema().itemToMap(item, true))
                .conditionExpression("attribute_not_exists(" + ScanItem.PK + ")")
                .build())
        .build();
  }

  private TransactWriteItem buildScanRowPut(ScanRowItem item) {
    return TransactWriteItem.builder()
        .put(
            Put.builder()
                .tableName(TcgInventoryTable.TABLE_NAME)
                .item(scanRowTable.tableSchema().itemToMap(item, true))
                .conditionExpression("attribute_not_exists(" + ScanRowItem.PK + ")")
                .build())
        .build();
  }

  private TransactWriteItem buildScanRowDelete(ScanRowItem item) {
    return TransactWriteItem.builder()
        .delete(
            Delete.builder()
                .tableName(TcgInventoryTable.TABLE_NAME)
                .key(
                    Map.of(
                        ScanRowItem.PK, AttributeValue.builder().s(item.getPk()).build(),
                        ScanRowItem.SK, AttributeValue.builder().s(item.getSk()).build()))
                .build())
        .build();
  }

  private void executeChunked(List<TransactWriteItem> transactItems) {
    for (int start = 0; start < transactItems.size(); start += MAX_TRANSACT_ITEMS) {
      var chunk =
          transactItems.subList(start, Math.min(start + MAX_TRANSACT_ITEMS, transactItems.size()));
      dynamoDbClient.transactWriteItems(
          TransactWriteItemsRequest.builder().transactItems(chunk).build());
    }
  }
}
