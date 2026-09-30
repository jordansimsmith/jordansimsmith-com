package com.jordansimsmith.tcginventory.imports;

import com.jordansimsmith.tcginventory.JobItem;
import com.jordansimsmith.tcginventory.TcgInventoryTable;
import java.time.Instant;
import java.util.Map;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.Put;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;
import software.amazon.awssdk.services.dynamodb.model.Update;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;

public class ImportsRepository {
  public enum ConfirmationStartResult {
    STARTED,
    CONFIRMING,
    CONFIRMED,
    NOT_FOUND
  }

  private final DynamoDbClient dynamoDbClient;
  private final DynamoDbTable<ImportItem> importTable;
  private final DynamoDbTable<JobItem> jobTable;

  public ImportsRepository(
      DynamoDbClient dynamoDbClient,
      DynamoDbTable<ImportItem> importTable,
      DynamoDbTable<JobItem> jobTable) {
    this.dynamoDbClient = dynamoDbClient;
    this.importTable = importTable;
    this.jobTable = jobTable;
  }

  public ConfirmationStartResult startConfirmation(
      String user, String importId, JobItem jobItem, Instant now) {
    var importKey =
        Key.builder()
            .partitionValue(ImportItem.formatPk(user))
            .sortValue(ImportItem.formatSk(importId))
            .build();
    try {
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
      return ConfirmationStartResult.STARTED;
    } catch (TransactionCanceledException e) {
      var currentImport =
          importTable.getItem(request -> request.key(importKey).consistentRead(true));
      if (currentImport == null) {
        return ConfirmationStartResult.NOT_FOUND;
      }
      if ("confirmed".equals(currentImport.getStatus())) {
        return ConfirmationStartResult.CONFIRMED;
      }
      if ("confirming".equals(currentImport.getStatus())) {
        return ConfirmationStartResult.CONFIRMING;
      }
      throw e;
    }
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
