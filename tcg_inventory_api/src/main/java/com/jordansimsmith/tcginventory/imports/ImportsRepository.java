package com.jordansimsmith.tcginventory.imports;

import com.jordansimsmith.tcginventory.JobItem;
import com.jordansimsmith.tcginventory.TcgInventoryTable;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionCheck;
import software.amazon.awssdk.services.dynamodb.model.Delete;
import software.amazon.awssdk.services.dynamodb.model.DeleteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.Put;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;
import software.amazon.awssdk.services.dynamodb.model.Update;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;

public class ImportsRepository {
  private static final TableSchema<ImportRowItem> ROW_SCHEMA =
      TableSchema.fromBean(ImportRowItem.class);
  private final DynamoDbClient dynamoDbClient;
  private final DynamoDbTable<JobItem> jobTable;

  public ImportsRepository(DynamoDbClient dynamoDbClient, DynamoDbTable<JobItem> jobTable) {
    this.dynamoDbClient = dynamoDbClient;
    this.jobTable = jobTable;
  }

  public void startConfirmation(String user, String importId, JobItem jobItem, Instant now) {
    dynamoDbClient.transactWriteItems(
        TransactWriteItemsRequest.builder()
            .transactItems(
                TransactWriteItem.builder()
                    .update(
                        Update.builder()
                            .tableName(TcgInventoryTable.TABLE_NAME)
                            .key(
                                Map.of(
                                    ImportItem.PK,
                                    AttributeValue.builder().s(ImportItem.formatPk(user)).build(),
                                    ImportItem.SK,
                                    AttributeValue.builder()
                                        .s(ImportItem.formatSk(importId))
                                        .build()))
                            .updateExpression("SET #status = :confirming, updated_at = :now")
                            .conditionExpression(
                                "attribute_exists(" + ImportItem.PK + ") AND #status = :review")
                            .expressionAttributeNames(Map.of("#status", ImportItem.STATUS))
                            .expressionAttributeValues(
                                Map.of(
                                    ":confirming",
                                    AttributeValue.builder().s("confirming").build(),
                                    ":review",
                                    AttributeValue.builder().s("review").build(),
                                    ":now",
                                    AttributeValue.builder()
                                        .n(String.valueOf(now.getEpochSecond()))
                                        .build()))
                            .build())
                    .build(),
                TransactWriteItem.builder()
                    .put(
                        Put.builder()
                            .tableName(TcgInventoryTable.TABLE_NAME)
                            .item(jobTable.tableSchema().itemToMap(jobItem, true))
                            .conditionExpression("attribute_not_exists(" + JobItem.PK + ")")
                            .build())
                    .build())
            .build());
  }

  public void updateRowCondition(String user, String importId, int position, String condition) {
    mutateReviewRow(
        user,
        importId,
        TransactWriteItem.builder()
            .update(
                Update.builder()
                    .tableName(TcgInventoryTable.TABLE_NAME)
                    .key(rowKey(user, importId, position))
                    .updateExpression("SET #condition = :condition")
                    .conditionExpression("attribute_exists(pk)")
                    .expressionAttributeNames(Map.of("#condition", ImportRowItem.CONDITION))
                    .expressionAttributeValues(
                        Map.of(":condition", AttributeValue.builder().s(condition).build()))
                    .build())
            .build());
  }

  public void deleteRow(String user, String importId, int position) {
    mutateReviewRow(
        user,
        importId,
        TransactWriteItem.builder()
            .delete(
                Delete.builder()
                    .tableName(TcgInventoryTable.TABLE_NAME)
                    .key(rowKey(user, importId, position))
                    .conditionExpression("attribute_exists(pk)")
                    .build())
            .build());
  }

  public void updateRowPhotos(
      String user, String importId, int position, List<ImportRowItem.Photo> photos) {
    var row = new ImportRowItem();
    row.setPhotos(photos);
    mutateReviewRow(
        user,
        importId,
        TransactWriteItem.builder()
            .update(
                Update.builder()
                    .tableName(TcgInventoryTable.TABLE_NAME)
                    .key(rowKey(user, importId, position))
                    .updateExpression("SET photos = :photos")
                    .conditionExpression("attribute_exists(pk)")
                    .expressionAttributeValues(
                        Map.of(
                            ":photos", ROW_SCHEMA.itemToMap(row, true).get(ImportRowItem.PHOTOS)))
                    .build())
            .build());
  }

  private Map<String, AttributeValue> rowKey(String user, String importId, int position) {
    return Map.of(
        ImportRowItem.PK,
        AttributeValue.builder().s(ImportRowItem.formatPk(user, importId)).build(),
        ImportRowItem.SK,
        AttributeValue.builder().s(ImportRowItem.formatSk(position)).build());
  }

  private void mutateReviewRow(String user, String importId, TransactWriteItem mutation) {
    dynamoDbClient.transactWriteItems(
        TransactWriteItemsRequest.builder()
            .transactItems(
                TransactWriteItem.builder()
                    .conditionCheck(
                        ConditionCheck.builder()
                            .tableName(TcgInventoryTable.TABLE_NAME)
                            .key(
                                Map.of(
                                    "pk",
                                    AttributeValue.builder().s(ImportItem.formatPk(user)).build(),
                                    "sk",
                                    AttributeValue.builder()
                                        .s(ImportItem.formatSk(importId))
                                        .build()))
                            .conditionExpression("attribute_exists(pk) AND #status = :review")
                            .expressionAttributeNames(Map.of("#status", "status"))
                            .expressionAttributeValues(
                                Map.of(":review", AttributeValue.builder().s("review").build()))
                            .build())
                    .build(),
                mutation)
            .build());
  }

  public void deleteReviewImport(String user, String importId) {
    dynamoDbClient.deleteItem(
        DeleteItemRequest.builder()
            .tableName(TcgInventoryTable.TABLE_NAME)
            .key(
                Map.of(
                    "pk",
                    AttributeValue.builder().s(ImportItem.formatPk(user)).build(),
                    "sk",
                    AttributeValue.builder().s(ImportItem.formatSk(importId)).build()))
            .conditionExpression("attribute_exists(pk) AND #status = :review")
            .expressionAttributeNames(Map.of("#status", "status"))
            .expressionAttributeValues(
                Map.of(":review", AttributeValue.builder().s("review").build()))
            .build());
  }

  public void finishConfirmation(String user, String importId, Instant now) {
    dynamoDbClient.updateItem(
        UpdateItemRequest.builder()
            .tableName(TcgInventoryTable.TABLE_NAME)
            .key(
                Map.of(
                    ImportItem.PK,
                    AttributeValue.builder().s(ImportItem.formatPk(user)).build(),
                    ImportItem.SK,
                    AttributeValue.builder().s(ImportItem.formatSk(importId)).build()))
            .updateExpression("SET #status = :confirmed, updated_at = :now")
            .conditionExpression("#status = :confirming")
            .expressionAttributeNames(Map.of("#status", ImportItem.STATUS))
            .expressionAttributeValues(
                Map.of(
                    ":confirmed", AttributeValue.builder().s("confirmed").build(),
                    ":confirming", AttributeValue.builder().s("confirming").build(),
                    ":now",
                        AttributeValue.builder().n(String.valueOf(now.getEpochSecond())).build()))
            .build());
  }
}
