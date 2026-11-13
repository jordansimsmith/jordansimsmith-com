package com.jordansimsmith.tcginventory.scans;

import com.jordansimsmith.tcginventory.JobItem;
import com.jordansimsmith.time.Clock;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryEnhancedRequest;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.Put;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;
import software.amazon.awssdk.services.dynamodb.model.Update;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;

public class ScanRepository {
  private static final int MAX_TRANSACT_ITEMS = 100;
  private static final TableSchema<JobItem> JOB_ITEM_SCHEMA = TableSchema.fromBean(JobItem.class);

  private final DynamoDbTable<ScanItem> scanTable;
  private final DynamoDbTable<ScanRowItem> scanRowTable;
  private final DynamoDbClient dynamoDbClient;
  private final Clock clock;
  private final String tableName;

  public ScanRepository(
      DynamoDbTable<ScanItem> scanTable,
      DynamoDbTable<ScanRowItem> scanRowTable,
      DynamoDbClient dynamoDbClient,
      Clock clock,
      String tableName) {
    this.scanTable = scanTable;
    this.scanRowTable = scanRowTable;
    this.dynamoDbClient = dynamoDbClient;
    this.clock = clock;
    this.tableName = tableName;
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

  public void updateScanRowSelection(
      String user, String scanId, int scanPosition, String externalId) {
    dynamoDbClient.updateItem(
        UpdateItemRequest.builder()
            .tableName(tableName)
            .key(
                Map.of(
                    ScanRowItem.PK,
                    AttributeValue.builder().s(ScanRowItem.formatPk(user, scanId)).build(),
                    ScanRowItem.SK,
                    AttributeValue.builder().s(ScanRowItem.formatSk(scanPosition)).build()))
            .updateExpression("SET " + ScanRowItem.SELECTED_EXTERNAL_ID + " = :externalId")
            .conditionExpression("attribute_exists(" + ScanRowItem.PK + ")")
            .expressionAttributeValues(
                Map.of(":externalId", AttributeValue.builder().s(externalId).build()))
            .build());
  }

  public void startScanConfirmation(
      String user, String scanId, long expectedVersion, JobItem jobItem) {
    var scanKey =
        Map.of(
            ScanItem.PK,
            AttributeValue.builder().s(ScanItem.formatPk(user)).build(),
            ScanItem.SK,
            AttributeValue.builder().s(ScanItem.formatSk(scanId)).build());
    dynamoDbClient.transactWriteItems(
        TransactWriteItemsRequest.builder()
            .transactItems(
                TransactWriteItem.builder()
                    .update(
                        Update.builder()
                            .tableName(tableName)
                            .key(scanKey)
                            .updateExpression(
                                "SET #status = :confirming, updated_at = :now REMOVE #error")
                            .conditionExpression(
                                "attribute_exists("
                                    + ScanItem.PK
                                    + ") AND #status = :reviewing AND (#version = :version OR "
                                    + "(attribute_not_exists(#version) AND :version = :zero))")
                            .expressionAttributeNames(
                                Map.of(
                                    "#status", ScanItem.STATUS,
                                    "#error", ScanItem.ERROR,
                                    "#version", ScanItem.VERSION))
                            .expressionAttributeValues(
                                Map.of(
                                    ":confirming",
                                    AttributeValue.builder().s("confirming").build(),
                                    ":reviewing",
                                    AttributeValue.builder().s("reviewing").build(),
                                    ":now",
                                    AttributeValue.builder()
                                        .n(String.valueOf(clock.now().getEpochSecond()))
                                        .build(),
                                    ":version",
                                    AttributeValue.fromN(Long.toString(expectedVersion)),
                                    ":zero",
                                    AttributeValue.fromN("0")))
                            .build())
                    .build(),
                TransactWriteItem.builder()
                    .put(
                        Put.builder()
                            .tableName(tableName)
                            .item(JOB_ITEM_SCHEMA.itemToMap(jobItem, true))
                            .conditionExpression("attribute_not_exists(" + JobItem.PK + ")")
                            .build())
                    .build())
            .build());
  }

  public boolean failScanConfirmation(String user, String scanId, String error) {
    try {
      dynamoDbClient.updateItem(
          UpdateItemRequest.builder()
              .tableName(tableName)
              .key(
                  Map.of(
                      ScanItem.PK,
                      AttributeValue.builder().s(ScanItem.formatPk(user)).build(),
                      ScanItem.SK,
                      AttributeValue.builder().s(ScanItem.formatSk(scanId)).build()))
              .updateExpression("SET #status = :reviewing, #error = :error, updated_at = :now")
              .conditionExpression(
                  "attribute_exists(" + ScanItem.PK + ") AND #status = :confirming")
              .expressionAttributeNames(
                  Map.of("#status", ScanItem.STATUS, "#error", ScanItem.ERROR))
              .expressionAttributeValues(
                  Map.of(
                      ":reviewing", AttributeValue.builder().s("reviewing").build(),
                      ":confirming", AttributeValue.builder().s("confirming").build(),
                      ":error", AttributeValue.builder().s(error).build(),
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
              .tableName(tableName)
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
              .conditionExpression(
                  "attribute_exists(" + ScanItem.PK + ") AND #status = :confirming")
              .expressionAttributeNames(Map.of("#status", ScanItem.STATUS))
              .expressionAttributeValues(
                  Map.of(
                      ":confirmed", AttributeValue.builder().s("confirmed").build(),
                      ":confirming", AttributeValue.builder().s("confirming").build(),
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
            .consistentRead(true)
            .build();
    return scanRowTable.query(request).stream().flatMap(page -> page.items().stream()).toList();
  }

  @Nullable
  public ScanItem getScan(String user, String scanId) {
    return scanTable.getItem(
        request ->
            request
                .key(
                    Key.builder()
                        .partitionValue(ScanItem.formatPk(user))
                        .sortValue(ScanItem.formatSk(scanId))
                        .build())
                .consistentRead(true));
  }

  private TransactWriteItem buildScanPut(ScanItem item) {
    return TransactWriteItem.builder()
        .put(
            Put.builder()
                .tableName(tableName)
                .item(scanTable.tableSchema().itemToMap(item, true))
                .conditionExpression("attribute_not_exists(" + ScanItem.PK + ")")
                .build())
        .build();
  }

  private TransactWriteItem buildScanRowPut(ScanRowItem item) {
    return TransactWriteItem.builder()
        .put(
            Put.builder()
                .tableName(tableName)
                .item(scanRowTable.tableSchema().itemToMap(item, true))
                .conditionExpression("attribute_not_exists(" + ScanRowItem.PK + ")")
                .build())
        .build();
  }
}
