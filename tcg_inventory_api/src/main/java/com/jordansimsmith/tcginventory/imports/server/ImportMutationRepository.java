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

  public void updateRowPhotos(
      String user, String importId, int position, List<ImportRowItem.Photo> photos) {
    var storedPhotos =
        photos.stream()
            .map(
                photo ->
                    AttributeValue.builder()
                        .m(
                            Map.of(
                                ImportRowItem.Photo.PHOTO_ID,
                                AttributeValue.fromS(photo.getPhotoId())))
                        .build())
            .toList();
    dynamoDbClient.transactWriteItems(
        TransactWriteItemsRequest.builder()
            .transactItems(
                TransactWriteItem.builder()
                    .conditionCheck(
                        ConditionCheck.builder()
                            .tableName(tableName)
                            .key(
                                Map.of(
                                    ImportItem.PK, AttributeValue.fromS(ImportItem.formatPk(user)),
                                    ImportItem.SK,
                                        AttributeValue.fromS(ImportItem.formatSk(importId))))
                            .conditionExpression("attribute_exists(pk) AND #status = :review")
                            .expressionAttributeNames(Map.of("#status", ImportItem.STATUS))
                            .expressionAttributeValues(
                                Map.of(":review", AttributeValue.fromS("review")))
                            .build())
                    .build(),
                TransactWriteItem.builder()
                    .update(
                        Update.builder()
                            .tableName(tableName)
                            .key(
                                Map.of(
                                    ImportRowItem.PK,
                                        AttributeValue.fromS(
                                            ImportRowItem.formatPk(user, importId)),
                                    ImportRowItem.SK,
                                        AttributeValue.fromS(ImportRowItem.formatSk(position))))
                            .updateExpression("SET #photos = :photos")
                            .conditionExpression("attribute_exists(pk)")
                            .expressionAttributeNames(Map.of("#photos", ImportRowItem.PHOTOS))
                            .expressionAttributeValues(
                                Map.of(":photos", AttributeValue.builder().l(storedPhotos).build()))
                            .build())
                    .build())
            .build());
  }
}
