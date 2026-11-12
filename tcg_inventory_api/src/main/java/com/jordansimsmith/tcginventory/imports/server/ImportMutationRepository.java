package com.jordansimsmith.tcginventory.imports.server;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
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

public class ImportMutationRepository {
  private final DynamoDbClient dynamoDbClient;
  private final String tableName;

  public ImportMutationRepository(DynamoDbClient dynamoDbClient, String tableName) {
    this.dynamoDbClient = dynamoDbClient;
    this.tableName = tableName;
  }

  public void createRows(List<ImportRowItem> rows) {
    var schema = TableSchema.fromBean(ImportRowItem.class);
    for (var row : rows) {
      dynamoDbClient.putItem(
          request -> request.tableName(tableName).item(schema.itemToMap(row, true)));
    }
  }

  public void createImport(ImportItem item, CreateImportOperationItem operation) {
    dynamoDbClient.transactWriteItems(
        TransactWriteItemsRequest.builder()
            .transactItems(
                TransactWriteItem.builder()
                    .put(
                        Put.builder()
                            .tableName(tableName)
                            .item(TableSchema.fromBean(ImportItem.class).itemToMap(item, true))
                            .conditionExpression("attribute_not_exists(pk)")
                            .build())
                    .build(),
                TransactWriteItem.builder()
                    .put(
                        Put.builder()
                            .tableName(tableName)
                            .item(
                                TableSchema.fromBean(CreateImportOperationItem.class)
                                    .itemToMap(operation, true))
                            .conditionExpression("attribute_not_exists(pk)")
                            .build())
                    .build())
            .build());
  }

  public void recordAppraisal(
      String user,
      String importId,
      int position,
      String decision,
      @Nullable String reason,
      @Nullable String marketPrice,
      @Nullable String suggestedPrice,
      @Nullable String fetchTcgCardId,
      @Nullable Integer fetchTcgSetId) {
    var names = new HashMap<String, String>();
    var values = new HashMap<String, AttributeValue>();
    var assignments = new ArrayList<String>();
    names.put("#decision", ImportRowItem.DECISION);
    values.put(":decision", AttributeValue.fromS(decision));
    assignments.add("#decision = :decision");
    if (reason != null) {
      names.put("#decisionReason", ImportRowItem.DECISION_REASON);
      values.put(":decisionReason", AttributeValue.fromS(reason));
      assignments.add("#decisionReason = :decisionReason");
    }
    if (marketPrice != null) {
      names.put("#marketPrice", ImportRowItem.MARKET_PRICE);
      values.put(":marketPrice", AttributeValue.fromS(marketPrice));
      assignments.add("#marketPrice = :marketPrice");
    }
    if (suggestedPrice != null) {
      names.put("#suggestedPrice", ImportRowItem.SUGGESTED_PRICE);
      values.put(":suggestedPrice", AttributeValue.fromS(suggestedPrice));
      assignments.add("#suggestedPrice = :suggestedPrice");
    }
    if (fetchTcgCardId != null) {
      names.put("#fetchTcgCardId", ImportRowItem.FETCHTCG_CARD_ID);
      values.put(":fetchTcgCardId", AttributeValue.fromS(fetchTcgCardId));
      assignments.add("#fetchTcgCardId = :fetchTcgCardId");
    }
    if (fetchTcgSetId != null) {
      names.put("#fetchTcgSetId", ImportRowItem.FETCHTCG_SET_ID);
      values.put(":fetchTcgSetId", AttributeValue.fromN(fetchTcgSetId.toString()));
      assignments.add("#fetchTcgSetId = :fetchTcgSetId");
    }
    dynamoDbClient.updateItem(
        UpdateItemRequest.builder()
            .tableName(tableName)
            .key(
                Map.of(
                    ImportRowItem.PK,
                    AttributeValue.fromS(ImportRowItem.formatPk(user, importId)),
                    ImportRowItem.SK,
                    AttributeValue.fromS(ImportRowItem.formatSk(position))))
            .updateExpression("SET " + String.join(", ", assignments))
            .conditionExpression("attribute_exists(pk)")
            .expressionAttributeNames(names)
            .expressionAttributeValues(values)
            .build());
  }

  public void recordCreatedUnit(
      String user,
      String importId,
      int position,
      String skuId,
      int sequenceNumber,
      String location) {
    dynamoDbClient.updateItem(
        UpdateItemRequest.builder()
            .tableName(tableName)
            .key(
                Map.of(
                    ImportRowItem.PK,
                    AttributeValue.fromS(ImportRowItem.formatPk(user, importId)),
                    ImportRowItem.SK,
                    AttributeValue.fromS(ImportRowItem.formatSk(position))))
            .updateExpression(
                "SET #skuId = :skuId, #sequenceNumber = :sequenceNumber, #location = :location")
            .conditionExpression("attribute_exists(pk) AND attribute_not_exists(#skuId)")
            .expressionAttributeNames(
                Map.of(
                    "#skuId", ImportRowItem.SKU_ID,
                    "#sequenceNumber", ImportRowItem.SEQUENCE_NUMBER,
                    "#location", ImportRowItem.LOCATION))
            .expressionAttributeValues(
                Map.of(
                    ":skuId", AttributeValue.fromS(skuId),
                    ":sequenceNumber", AttributeValue.fromN(Integer.toString(sequenceNumber)),
                    ":location", AttributeValue.fromS(location)))
            .build());
  }

  public void finishConfirmation(String user, String importId, Instant now) {
    dynamoDbClient.updateItem(
        UpdateItemRequest.builder()
            .tableName(tableName)
            .key(
                Map.of(
                    ImportItem.PK, AttributeValue.fromS(ImportItem.formatPk(user)),
                    ImportItem.SK, AttributeValue.fromS(ImportItem.formatSk(importId))))
            .updateExpression("SET #status = :confirmed, #updatedAt = :updatedAt")
            .conditionExpression("attribute_exists(pk) AND #status = :confirming")
            .expressionAttributeNames(
                Map.of("#status", ImportItem.STATUS, "#updatedAt", ImportItem.UPDATED_AT))
            .expressionAttributeValues(
                Map.of(
                    ":confirmed", AttributeValue.fromS("confirmed"),
                    ":confirming", AttributeValue.fromS("confirming"),
                    ":updatedAt", AttributeValue.fromN(Long.toString(now.getEpochSecond()))))
            .build());
  }

  public void startConfirmation(String user, String importId, Instant now) {
    dynamoDbClient.updateItem(
        UpdateItemRequest.builder()
            .tableName(tableName)
            .key(
                Map.of(
                    ImportItem.PK, AttributeValue.fromS(ImportItem.formatPk(user)),
                    ImportItem.SK, AttributeValue.fromS(ImportItem.formatSk(importId))))
            .updateExpression("SET #status = :confirming, #updatedAt = :updatedAt")
            .conditionExpression("attribute_exists(pk) AND #status = :review")
            .expressionAttributeNames(
                Map.of("#status", ImportItem.STATUS, "#updatedAt", ImportItem.UPDATED_AT))
            .expressionAttributeValues(
                Map.of(
                    ":review", AttributeValue.fromS("review"),
                    ":confirming", AttributeValue.fromS("confirming"),
                    ":updatedAt", AttributeValue.fromN(Long.toString(now.getEpochSecond()))))
            .build());
  }

  public void checkpointAppraisal(
      String user, String importId, int expectedPosition, int position, Instant now) {
    dynamoDbClient.updateItem(
        UpdateItemRequest.builder()
            .tableName(tableName)
            .key(
                Map.of(
                    ImportItem.PK, AttributeValue.fromS(ImportItem.formatPk(user)),
                    ImportItem.SK, AttributeValue.fromS(ImportItem.formatSk(importId))))
            .updateExpression("SET #appraised = :position, #updatedAt = :updatedAt")
            .conditionExpression(
                "attribute_exists(pk) AND #status = :appraising AND #appraised = :expected "
                    + "AND #appraised < :position")
            .expressionAttributeNames(
                Map.of(
                    "#status", ImportItem.STATUS,
                    "#appraised", ImportItem.APPRAISED_ROW_COUNT,
                    "#updatedAt", ImportItem.UPDATED_AT))
            .expressionAttributeValues(
                Map.of(
                    ":appraising", AttributeValue.fromS("appraising"),
                    ":expected", AttributeValue.fromN(Integer.toString(expectedPosition)),
                    ":position", AttributeValue.fromN(Integer.toString(position)),
                    ":updatedAt", AttributeValue.fromN(Long.toString(now.getEpochSecond()))))
            .build());
  }

  public void finishAppraisal(
      String user, String importId, int expectedPosition, int position, Instant now) {
    dynamoDbClient.updateItem(
        UpdateItemRequest.builder()
            .tableName(tableName)
            .key(
                Map.of(
                    ImportItem.PK, AttributeValue.fromS(ImportItem.formatPk(user)),
                    ImportItem.SK, AttributeValue.fromS(ImportItem.formatSk(importId))))
            .updateExpression(
                "SET #appraised = :position, #updatedAt = :updatedAt, #status = :review")
            .conditionExpression(
                "attribute_exists(pk) AND #status = :appraising AND #appraised = :expected "
                    + "AND #appraised <= :position")
            .expressionAttributeNames(
                Map.of(
                    "#status", ImportItem.STATUS,
                    "#appraised", ImportItem.APPRAISED_ROW_COUNT,
                    "#updatedAt", ImportItem.UPDATED_AT))
            .expressionAttributeValues(
                Map.of(
                    ":appraising", AttributeValue.fromS("appraising"),
                    ":expected", AttributeValue.fromN(Integer.toString(expectedPosition)),
                    ":position", AttributeValue.fromN(Integer.toString(position)),
                    ":updatedAt", AttributeValue.fromN(Long.toString(now.getEpochSecond())),
                    ":review", AttributeValue.fromS("review")))
            .build());
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
