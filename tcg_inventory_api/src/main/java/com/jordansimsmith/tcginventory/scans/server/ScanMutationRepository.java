package com.jordansimsmith.tcginventory.scans.server;

import com.jordansimsmith.time.Clock;
import java.util.List;
import java.util.Map;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.Delete;
import software.amazon.awssdk.services.dynamodb.model.DeleteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;
import software.amazon.awssdk.services.dynamodb.model.Update;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;

public class ScanMutationRepository {
  private final DynamoDbTable<ScanItem> scanTable;
  private final DynamoDbTable<ScanRowItem> scanRowTable;
  private final DynamoDbClient dynamoDbClient;
  private final Clock clock;
  private final String tableName;

  public ScanMutationRepository(
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

  public void createScanRow(ScanRowItem rowItem) {
    dynamoDbClient.putItem(
        request ->
            request
                .tableName(tableName)
                .item(scanRowTable.tableSchema().itemToMap(rowItem, true))
                .conditionExpression("attribute_not_exists(" + ScanRowItem.PK + ")"));
  }

  public void createScan(ScanItem scanItem) {
    dynamoDbClient.putItem(
        request ->
            request
                .tableName(tableName)
                .item(scanTable.tableSchema().itemToMap(scanItem, true))
                .conditionExpression("attribute_not_exists(" + ScanItem.PK + ")"));
  }

  public void startScanIdentification(String user, String scanId) {
    dynamoDbClient.updateItem(
        UpdateItemRequest.builder()
            .tableName(tableName)
            .key(
                Map.of(
                    ScanItem.PK, AttributeValue.fromS(ScanItem.formatPk(user)),
                    ScanItem.SK, AttributeValue.fromS(ScanItem.formatSk(scanId))))
            .updateExpression("SET #status = :identifying, updated_at = :now")
            .conditionExpression("attribute_exists(" + ScanItem.PK + ") AND #status = :uploading")
            .expressionAttributeNames(Map.of("#status", ScanItem.STATUS))
            .expressionAttributeValues(
                Map.of(
                    ":identifying", AttributeValue.fromS("identifying"),
                    ":uploading", AttributeValue.fromS("uploading"),
                    ":now", AttributeValue.fromN(Long.toString(clock.now().getEpochSecond()))))
            .build());
  }

  public void deleteScanRow(String user, String scanId, int scanPosition) {
    var scanKey =
        Map.of(
            ScanItem.PK, AttributeValue.fromS(ScanItem.formatPk(user)),
            ScanItem.SK, AttributeValue.fromS(ScanItem.formatSk(scanId)));
    var rowKey =
        Map.of(
            ScanRowItem.PK, AttributeValue.fromS(ScanRowItem.formatPk(user, scanId)),
            ScanRowItem.SK, AttributeValue.fromS(ScanRowItem.formatSk(scanPosition)));
    dynamoDbClient.transactWriteItems(
        TransactWriteItemsRequest.builder()
            .transactItems(
                List.of(
                    TransactWriteItem.builder()
                        .update(
                            Update.builder()
                                .tableName(tableName)
                                .key(scanKey)
                                .updateExpression(
                                    "SET #version = if_not_exists(#version, :zero) + :one, "
                                        + "updated_at = :now")
                                .conditionExpression(
                                    "attribute_exists("
                                        + ScanItem.PK
                                        + ") AND #status = :reviewing")
                                .expressionAttributeNames(
                                    Map.of(
                                        "#version", ScanItem.VERSION, "#status", ScanItem.STATUS))
                                .expressionAttributeValues(
                                    Map.of(
                                        ":zero", AttributeValue.fromN("0"),
                                        ":one", AttributeValue.fromN("1"),
                                        ":now",
                                            AttributeValue.fromN(
                                                Long.toString(clock.now().getEpochSecond())),
                                        ":reviewing", AttributeValue.fromS("reviewing")))
                                .build())
                        .build(),
                    TransactWriteItem.builder()
                        .delete(
                            Delete.builder()
                                .tableName(tableName)
                                .key(rowKey)
                                .conditionExpression("attribute_exists(" + ScanRowItem.PK + ")")
                                .build())
                        .build()))
            .build());
  }

  public void deleteScan(String user, String scanId) {
    dynamoDbClient.deleteItem(
        DeleteItemRequest.builder()
            .tableName(tableName)
            .key(
                Map.of(
                    ScanItem.PK, AttributeValue.fromS(ScanItem.formatPk(user)),
                    ScanItem.SK, AttributeValue.fromS(ScanItem.formatSk(scanId))))
            .conditionExpression("attribute_exists(" + ScanItem.PK + ") AND #status = :reviewing")
            .expressionAttributeNames(Map.of("#status", ScanItem.STATUS))
            .expressionAttributeValues(Map.of(":reviewing", AttributeValue.fromS("reviewing")))
            .build());
  }

  public void deleteScanRow(ScanRowItem rowItem) {
    dynamoDbClient.deleteItem(
        DeleteItemRequest.builder()
            .tableName(tableName)
            .key(
                Map.of(
                    ScanRowItem.PK, AttributeValue.fromS(rowItem.getPk()),
                    ScanRowItem.SK, AttributeValue.fromS(rowItem.getSk())))
            .build());
  }
}
