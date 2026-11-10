package com.jordansimsmith.tcginventory.publish.server;

import java.time.Instant;
import java.util.Map;
import javax.annotation.Nullable;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.model.GetItemEnhancedRequest;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;

public class PublishRepository {
  private final DynamoDbTable<PublishItem> publishTable;
  private final DynamoDbClient dynamoDbClient;
  private final String tableName;

  public PublishRepository(
      DynamoDbTable<PublishItem> publishTable, DynamoDbClient dynamoDbClient, String tableName) {
    this.publishTable = publishTable;
    this.dynamoDbClient = dynamoDbClient;
    this.tableName = tableName;
  }

  @Nullable
  public PublishItem get(String user) {
    var key =
        Key.builder()
            .partitionValue(PublishItem.formatPk(user))
            .sortValue(PublishItem.formatSk())
            .build();
    return publishTable.getItem(
        GetItemEnhancedRequest.builder().key(key).consistentRead(true).build());
  }

  public void createPublish(String user, String publishId, Instant startedAt) {
    dynamoDbClient.updateItem(
        UpdateItemRequest.builder()
            .tableName(tableName)
            .key(
                Map.of(
                    PublishItem.PK,
                    AttributeValue.fromS(PublishItem.formatPk(user)),
                    PublishItem.SK,
                    AttributeValue.fromS(PublishItem.formatSk())))
            .conditionExpression("attribute_not_exists(#status) OR #status = :succeeded")
            .updateExpression(
                "SET #user = :user, #publish_id = :publish_id, #status = :queued, "
                    + "#published_sku_count = :zero, "
                    + "#started_at = :started_at, #updated_at = :started_at "
                    + "REMOVE #finished_at")
            .expressionAttributeNames(
                Map.of(
                    "#user", PublishItem.USER,
                    "#publish_id", PublishItem.PUBLISH_ID,
                    "#status", PublishItem.STATUS,
                    "#published_sku_count", PublishItem.PUBLISHED_SKU_COUNT,
                    "#started_at", PublishItem.STARTED_AT,
                    "#updated_at", PublishItem.UPDATED_AT,
                    "#finished_at", PublishItem.FINISHED_AT))
            .expressionAttributeValues(
                Map.of(
                    ":user", AttributeValue.fromS(user),
                    ":publish_id", AttributeValue.fromS(publishId),
                    ":queued", AttributeValue.fromS("queued"),
                    ":succeeded", AttributeValue.fromS("succeeded"),
                    ":zero", AttributeValue.fromN("0"),
                    ":started_at", AttributeValue.fromN(Long.toString(startedAt.getEpochSecond()))))
            .build());
  }

  public void startPublish(String user, String publishId, Instant updatedAt) {
    dynamoDbClient.updateItem(
        UpdateItemRequest.builder()
            .tableName(tableName)
            .key(
                Map.of(
                    PublishItem.PK,
                    AttributeValue.fromS(PublishItem.formatPk(user)),
                    PublishItem.SK,
                    AttributeValue.fromS(PublishItem.formatSk())))
            .conditionExpression("#publish_id = :publish_id AND #status = :queued")
            .updateExpression("SET #status = :running, #updated_at = :updated_at")
            .expressionAttributeNames(
                Map.of(
                    "#publish_id", PublishItem.PUBLISH_ID,
                    "#status", PublishItem.STATUS,
                    "#updated_at", PublishItem.UPDATED_AT))
            .expressionAttributeValues(
                Map.of(
                    ":publish_id", AttributeValue.fromS(publishId),
                    ":queued", AttributeValue.fromS("queued"),
                    ":running", AttributeValue.fromS("running"),
                    ":updated_at", AttributeValue.fromN(Long.toString(updatedAt.getEpochSecond()))))
            .build());
  }

  public void checkpointPublish(
      String user,
      String publishId,
      int expectedPublishedSkuCount,
      int publishedSkuCount,
      Instant updatedAt) {
    if (publishedSkuCount <= expectedPublishedSkuCount) {
      throw new IllegalArgumentException("checkpoint must advance published_sku_count");
    }
    dynamoDbClient.updateItem(
        UpdateItemRequest.builder()
            .tableName(tableName)
            .key(
                Map.of(
                    PublishItem.PK,
                    AttributeValue.fromS(PublishItem.formatPk(user)),
                    PublishItem.SK,
                    AttributeValue.fromS(PublishItem.formatSk())))
            .conditionExpression(
                "#publish_id = :publish_id AND #status = :running "
                    + "AND #published_sku_count = :expected_published_sku_count")
            .updateExpression(
                "SET #published_sku_count = :published_sku_count, #updated_at = :updated_at")
            .expressionAttributeNames(
                Map.of(
                    "#publish_id", PublishItem.PUBLISH_ID,
                    "#status", PublishItem.STATUS,
                    "#published_sku_count", PublishItem.PUBLISHED_SKU_COUNT,
                    "#updated_at", PublishItem.UPDATED_AT))
            .expressionAttributeValues(
                Map.of(
                    ":publish_id", AttributeValue.fromS(publishId),
                    ":running", AttributeValue.fromS("running"),
                    ":expected_published_sku_count",
                        AttributeValue.fromN(Integer.toString(expectedPublishedSkuCount)),
                    ":published_sku_count",
                        AttributeValue.fromN(Integer.toString(publishedSkuCount)),
                    ":updated_at", AttributeValue.fromN(Long.toString(updatedAt.getEpochSecond()))))
            .build());
  }

  public void completePublish(
      String user,
      String publishId,
      int expectedPublishedSkuCount,
      int publishedSkuCount,
      Instant finishedAt) {
    if (publishedSkuCount < expectedPublishedSkuCount) {
      throw new IllegalArgumentException("published_sku_count cannot decrease");
    }
    dynamoDbClient.updateItem(
        UpdateItemRequest.builder()
            .tableName(tableName)
            .key(
                Map.of(
                    PublishItem.PK,
                    AttributeValue.fromS(PublishItem.formatPk(user)),
                    PublishItem.SK,
                    AttributeValue.fromS(PublishItem.formatSk())))
            .conditionExpression(
                "#publish_id = :publish_id AND #status = :running "
                    + "AND #published_sku_count = :expected_published_sku_count")
            .updateExpression(
                "SET #published_sku_count = :published_sku_count, #status = :succeeded, "
                    + "#updated_at = :finished_at, #finished_at = :finished_at")
            .expressionAttributeNames(
                Map.of(
                    "#publish_id", PublishItem.PUBLISH_ID,
                    "#status", PublishItem.STATUS,
                    "#published_sku_count", PublishItem.PUBLISHED_SKU_COUNT,
                    "#updated_at", PublishItem.UPDATED_AT,
                    "#finished_at", PublishItem.FINISHED_AT))
            .expressionAttributeValues(
                Map.of(
                    ":publish_id", AttributeValue.fromS(publishId),
                    ":running", AttributeValue.fromS("running"),
                    ":published_sku_count",
                        AttributeValue.fromN(Integer.toString(publishedSkuCount)),
                    ":succeeded", AttributeValue.fromS("succeeded"),
                    ":expected_published_sku_count",
                        AttributeValue.fromN(Integer.toString(expectedPublishedSkuCount)),
                    ":finished_at",
                        AttributeValue.fromN(Long.toString(finishedAt.getEpochSecond()))))
            .build());
  }
}
