package com.jordansimsmith.tcginventory.inventory.server;

import com.jordansimsmith.time.Clock;
import com.jordansimsmith.ulid.UlidGenerator;
import java.util.HashMap;
import java.util.Map;
import javax.annotation.Nullable;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.Put;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;
import software.amazon.awssdk.services.dynamodb.model.Update;

public class InventoryMutationRepository {
  private final DynamoDbClient dynamoDbClient;
  private final String tableName;
  private final Clock clock;
  private final UlidGenerator ulidGenerator;

  public InventoryMutationRepository(
      DynamoDbClient dynamoDbClient, String tableName, Clock clock, UlidGenerator ulidGenerator) {
    this.dynamoDbClient = dynamoDbClient;
    this.tableName = tableName;
    this.clock = clock;
    this.ulidGenerator = ulidGenerator;
  }

  public void deleteUnit(String user, String skuId, int sequenceNumber, @Nullable String reason) {
    var skuPk = "USER#" + user + "#SKU#" + skuId;
    var unitSk = "UNIT#%010d".formatted(sequenceNumber);
    var now = String.valueOf(clock.now().getEpochSecond());

    var audit = new HashMap<String, AttributeValue>();
    audit.put("pk", AttributeValue.builder().s("USER#" + user + "#AUDIT").build());
    audit.put("sk", AttributeValue.builder().s(ulidGenerator.generate()).build());
    audit.put("event_type", AttributeValue.builder().s("adjustment").build());
    audit.put("sku_id", AttributeValue.builder().s(skuId).build());
    audit.put(
        "sequence_number", AttributeValue.builder().n(String.valueOf(sequenceNumber)).build());
    audit.put("before_status", AttributeValue.builder().s("in_stock").build());
    audit.put("after_status", AttributeValue.builder().s("removed").build());
    audit.put("created_at", AttributeValue.builder().n(now).build());
    if (reason != null && !reason.isEmpty()) {
      audit.put("decision_reason", AttributeValue.builder().s(reason).build());
    }

    dynamoDbClient.transactWriteItems(
        TransactWriteItemsRequest.builder()
            .transactItems(
                TransactWriteItem.builder()
                    .update(
                        Update.builder()
                            .tableName(tableName)
                            .key(
                                Map.of(
                                    "pk", AttributeValue.builder().s(skuPk).build(),
                                    "sk", AttributeValue.builder().s(unitSk).build()))
                            .updateExpression("SET #status = :removed, updated_at = :now")
                            .conditionExpression("#status = :inStock")
                            .expressionAttributeNames(Map.of("#status", "status"))
                            .expressionAttributeValues(
                                Map.of(
                                    ":removed", AttributeValue.builder().s("removed").build(),
                                    ":inStock", AttributeValue.builder().s("in_stock").build(),
                                    ":now", AttributeValue.builder().n(now).build()))
                            .build())
                    .build(),
                TransactWriteItem.builder()
                    .update(
                        Update.builder()
                            .tableName(tableName)
                            .key(
                                Map.of(
                                    "pk", AttributeValue.builder().s(skuPk).build(),
                                    "sk", AttributeValue.builder().s("SKU").build()))
                            .updateExpression(
                                "SET dirty = :dirty, gsi1pk = :gsi1pk ADD #version :one")
                            .conditionExpression("attribute_exists(pk)")
                            .expressionAttributeNames(Map.of("#version", "version"))
                            .expressionAttributeValues(
                                Map.of(
                                    ":dirty", AttributeValue.builder().bool(true).build(),
                                    ":gsi1pk",
                                        AttributeValue.builder()
                                            .s("USER#" + user + "#DIRTY")
                                            .build(),
                                    ":one", AttributeValue.builder().n("1").build()))
                            .build())
                    .build(),
                TransactWriteItem.builder()
                    .put(Put.builder().tableName(tableName).item(audit).build())
                    .build())
            .build());
  }
}
