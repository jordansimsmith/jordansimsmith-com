package com.jordansimsmith.tcginventory.imports.server;

import java.util.List;
import java.util.Map;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionCheck;
import software.amazon.awssdk.services.dynamodb.model.Delete;
import software.amazon.awssdk.services.dynamodb.model.DeleteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;
import software.amazon.awssdk.services.dynamodb.model.Update;

public class ImportMutationRepository {
  private final DynamoDbClient dynamoDbClient;
  private final String tableName;

  public ImportMutationRepository(DynamoDbClient dynamoDbClient, String tableName) {
    this.dynamoDbClient = dynamoDbClient;
    this.tableName = tableName;
  }

  public void updateRowCondition(String user, String importId, int position, String condition) {
    dynamoDbClient.transactWriteItems(
        TransactWriteItemsRequest.builder()
            .transactItems(
                TransactWriteItem.builder()
                    .conditionCheck(
                        ConditionCheck.builder()
                            .tableName(tableName)
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
                TransactWriteItem.builder()
                    .update(
                        Update.builder()
                            .tableName(tableName)
                            .key(
                                Map.of(
                                    "pk",
                                    AttributeValue.builder()
                                        .s(ImportRowItem.formatPk(user, importId))
                                        .build(),
                                    "sk",
                                    AttributeValue.builder()
                                        .s(ImportRowItem.formatSk(position))
                                        .build()))
                            .updateExpression("SET #condition = :condition")
                            .conditionExpression("attribute_exists(pk)")
                            .expressionAttributeNames(Map.of("#condition", "condition"))
                            .expressionAttributeValues(
                                Map.of(":condition", AttributeValue.builder().s(condition).build()))
                            .build())
                    .build())
            .build());
  }

  public void deleteRow(String user, String importId, int position) {
    dynamoDbClient.transactWriteItems(
        TransactWriteItemsRequest.builder()
            .transactItems(
                TransactWriteItem.builder()
                    .conditionCheck(
                        ConditionCheck.builder()
                            .tableName(tableName)
                            .key(
                                Map.of(
                                    ImportItem.PK,
                                    AttributeValue.builder().s(ImportItem.formatPk(user)).build(),
                                    ImportItem.SK,
                                    AttributeValue.builder()
                                        .s(ImportItem.formatSk(importId))
                                        .build()))
                            .conditionExpression("attribute_exists(pk) AND #status = :review")
                            .expressionAttributeNames(Map.of("#status", ImportItem.STATUS))
                            .expressionAttributeValues(
                                Map.of(":review", AttributeValue.builder().s("review").build()))
                            .build())
                    .build(),
                TransactWriteItem.builder()
                    .delete(
                        Delete.builder()
                            .tableName(tableName)
                            .key(
                                Map.of(
                                    ImportRowItem.PK,
                                    AttributeValue.builder()
                                        .s(ImportRowItem.formatPk(user, importId))
                                        .build(),
                                    ImportRowItem.SK,
                                    AttributeValue.builder()
                                        .s(ImportRowItem.formatSk(position))
                                        .build()))
                            .conditionExpression("attribute_exists(pk)")
                            .build())
                    .build())
            .build());
  }

  public void deleteImport(String user, String importId) {
    dynamoDbClient.deleteItem(
        DeleteItemRequest.builder()
            .tableName(tableName)
            .key(
                Map.of(
                    ImportItem.PK,
                    AttributeValue.builder().s(ImportItem.formatPk(user)).build(),
                    ImportItem.SK,
                    AttributeValue.builder().s(ImportItem.formatSk(importId)).build()))
            .conditionExpression("attribute_exists(pk) AND #status = :review")
            .expressionAttributeNames(Map.of("#status", ImportItem.STATUS))
            .expressionAttributeValues(
                Map.of(":review", AttributeValue.builder().s("review").build()))
            .build());
  }

  public void deleteRows(List<ImportRowItem> rows) {
    for (var row : rows) {
      dynamoDbClient.deleteItem(
          DeleteItemRequest.builder()
              .tableName(tableName)
              .key(
                  Map.of(
                      ImportRowItem.PK, AttributeValue.builder().s(row.getPk()).build(),
                      ImportRowItem.SK, AttributeValue.builder().s(row.getSk()).build()))
              .build());
    }
  }
}
