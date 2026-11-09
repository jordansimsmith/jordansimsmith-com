package com.jordansimsmith.tcginventory.pricing.server;

import java.time.Instant;
import java.util.Map;
import javax.annotation.Nullable;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.model.GetItemEnhancedRequest;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;

public class RepricingRepository {
  private final DynamoDbTable<RepricingItem> repricingTable;
  private final DynamoDbClient dynamoDbClient;
  private final String tableName;

  public RepricingRepository(
      DynamoDbTable<RepricingItem> repricingTable,
      DynamoDbClient dynamoDbClient,
      String tableName) {
    this.repricingTable = repricingTable;
    this.dynamoDbClient = dynamoDbClient;
    this.tableName = tableName;
  }

  @Nullable
  public RepricingItem get(String user) {
    var key =
        Key.builder()
            .partitionValue(RepricingItem.formatPk(user))
            .sortValue(RepricingItem.formatSk())
            .build();
    return repricingTable.getItem(
        GetItemEnhancedRequest.builder().key(key).consistentRead(true).build());
  }

  public void createRepricing(String user, String repricingId, Instant startedAt) {
    dynamoDbClient.updateItem(
        UpdateItemRequest.builder()
            .tableName(tableName)
            .key(
                Map.of(
                    RepricingItem.PK,
                    AttributeValue.fromS(RepricingItem.formatPk(user)),
                    RepricingItem.SK,
                    AttributeValue.fromS(RepricingItem.formatSk())))
            .conditionExpression("attribute_not_exists(#status) OR #status = :succeeded")
            .updateExpression(
                "SET #user = :user, #repricing_id = :repricing_id, #status = :queued, "
                    + "#processed_sku_count = :zero, "
                    + "#started_at = :started_at, #updated_at = :started_at "
                    + "REMOVE #continuation, #finished_at")
            .expressionAttributeNames(
                Map.of(
                    "#user", RepricingItem.USER,
                    "#repricing_id", RepricingItem.REPRICING_ID,
                    "#status", RepricingItem.STATUS,
                    "#processed_sku_count", RepricingItem.PROCESSED_SKU_COUNT,
                    "#started_at", RepricingItem.STARTED_AT,
                    "#updated_at", RepricingItem.UPDATED_AT,
                    "#continuation", RepricingItem.CONTINUATION,
                    "#finished_at", RepricingItem.FINISHED_AT))
            .expressionAttributeValues(
                Map.of(
                    ":user", AttributeValue.fromS(user),
                    ":repricing_id", AttributeValue.fromS(repricingId),
                    ":queued", AttributeValue.fromS("queued"),
                    ":succeeded", AttributeValue.fromS("succeeded"),
                    ":zero", AttributeValue.fromN("0"),
                    ":started_at", AttributeValue.fromN(Long.toString(startedAt.getEpochSecond()))))
            .build());
  }

  public void startRepricing(String user, String repricingId, Instant updatedAt) {
    dynamoDbClient.updateItem(
        UpdateItemRequest.builder()
            .tableName(tableName)
            .key(
                Map.of(
                    RepricingItem.PK,
                    AttributeValue.fromS(RepricingItem.formatPk(user)),
                    RepricingItem.SK,
                    AttributeValue.fromS(RepricingItem.formatSk())))
            .conditionExpression("#repricing_id = :repricing_id AND #status = :queued")
            .updateExpression("SET #status = :running, #updated_at = :updated_at")
            .expressionAttributeNames(
                Map.of(
                    "#repricing_id", RepricingItem.REPRICING_ID,
                    "#status", RepricingItem.STATUS,
                    "#updated_at", RepricingItem.UPDATED_AT))
            .expressionAttributeValues(
                Map.of(
                    ":repricing_id", AttributeValue.fromS(repricingId),
                    ":queued", AttributeValue.fromS("queued"),
                    ":running", AttributeValue.fromS("running"),
                    ":updated_at", AttributeValue.fromN(Long.toString(updatedAt.getEpochSecond()))))
            .build());
  }

  public void checkpointRepricing(
      String user,
      String repricingId,
      int expectedProcessedSkuCount,
      int processedSkuCount,
      String continuation,
      Instant updatedAt) {
    if (processedSkuCount <= expectedProcessedSkuCount) {
      throw new IllegalArgumentException("checkpoint must advance processed_sku_count");
    }
    dynamoDbClient.updateItem(
        UpdateItemRequest.builder()
            .tableName(tableName)
            .key(
                Map.of(
                    RepricingItem.PK,
                    AttributeValue.fromS(RepricingItem.formatPk(user)),
                    RepricingItem.SK,
                    AttributeValue.fromS(RepricingItem.formatSk())))
            .conditionExpression(
                "#repricing_id = :repricing_id AND #status = :running "
                    + "AND #processed_sku_count = :expected_processed_sku_count")
            .updateExpression(
                "SET #processed_sku_count = :processed_sku_count, #continuation = :continuation, "
                    + "#updated_at = :updated_at")
            .expressionAttributeNames(
                Map.of(
                    "#repricing_id", RepricingItem.REPRICING_ID,
                    "#status", RepricingItem.STATUS,
                    "#processed_sku_count", RepricingItem.PROCESSED_SKU_COUNT,
                    "#continuation", RepricingItem.CONTINUATION,
                    "#updated_at", RepricingItem.UPDATED_AT))
            .expressionAttributeValues(
                Map.of(
                    ":repricing_id", AttributeValue.fromS(repricingId),
                    ":running", AttributeValue.fromS("running"),
                    ":expected_processed_sku_count",
                        AttributeValue.fromN(Integer.toString(expectedProcessedSkuCount)),
                    ":processed_sku_count",
                        AttributeValue.fromN(Integer.toString(processedSkuCount)),
                    ":continuation", AttributeValue.fromS(continuation),
                    ":updated_at", AttributeValue.fromN(Long.toString(updatedAt.getEpochSecond()))))
            .build());
  }

  public void completeRepricing(
      String user,
      String repricingId,
      int expectedProcessedSkuCount,
      int processedSkuCount,
      Instant finishedAt) {
    if (processedSkuCount < expectedProcessedSkuCount) {
      throw new IllegalArgumentException("processed_sku_count cannot decrease");
    }
    dynamoDbClient.updateItem(
        UpdateItemRequest.builder()
            .tableName(tableName)
            .key(
                Map.of(
                    RepricingItem.PK,
                    AttributeValue.fromS(RepricingItem.formatPk(user)),
                    RepricingItem.SK,
                    AttributeValue.fromS(RepricingItem.formatSk())))
            .conditionExpression(
                "#repricing_id = :repricing_id AND #status = :running "
                    + "AND #processed_sku_count = :expected_processed_sku_count")
            .updateExpression(
                "SET #processed_sku_count = :processed_sku_count, #status = :succeeded, "
                    + "#updated_at = :finished_at, #finished_at = :finished_at "
                    + "REMOVE #continuation")
            .expressionAttributeNames(
                Map.of(
                    "#repricing_id", RepricingItem.REPRICING_ID,
                    "#status", RepricingItem.STATUS,
                    "#processed_sku_count", RepricingItem.PROCESSED_SKU_COUNT,
                    "#updated_at", RepricingItem.UPDATED_AT,
                    "#finished_at", RepricingItem.FINISHED_AT,
                    "#continuation", RepricingItem.CONTINUATION))
            .expressionAttributeValues(
                Map.of(
                    ":repricing_id", AttributeValue.fromS(repricingId),
                    ":running", AttributeValue.fromS("running"),
                    ":expected_processed_sku_count",
                        AttributeValue.fromN(Integer.toString(expectedProcessedSkuCount)),
                    ":processed_sku_count",
                        AttributeValue.fromN(Integer.toString(processedSkuCount)),
                    ":succeeded", AttributeValue.fromS("succeeded"),
                    ":finished_at",
                        AttributeValue.fromN(Long.toString(finishedAt.getEpochSecond()))))
            .build());
  }
}
