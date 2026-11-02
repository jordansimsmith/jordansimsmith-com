package com.jordansimsmith.tcginventory.inventory.server;

import com.jordansimsmith.time.Clock;
import com.jordansimsmith.ulid.UlidGenerator;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import javax.annotation.Nullable;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.Delete;
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
    var now = clock.now();
    var audit =
        TableSchema.fromBean(InventoryAuditItem.class)
            .itemToMap(
                InventoryAuditItem.create(
                    user,
                    ulidGenerator.generate(),
                    "adjustment",
                    skuId,
                    sequenceNumber,
                    reason == null || reason.isEmpty() ? null : reason,
                    "in_stock",
                    "removed",
                    null,
                    now),
                true);

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
                                    ":removed",
                                    AttributeValue.builder().s("removed").build(),
                                    ":inStock",
                                    AttributeValue.builder().s("in_stock").build(),
                                    ":now",
                                    AttributeValue.builder()
                                        .n(Long.toString(now.getEpochSecond()))
                                        .build()))
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

  public void updateUnitCondition(
      String user,
      InventorySkuItem sourceSku,
      InventoryUnitItem sourceUnit,
      String destinationSkuId,
      String condition) {
    var now = clock.now();
    var sourcePk = InventorySkuItem.formatPk(user, sourceSku.getSkuId());
    var destinationPk = InventorySkuItem.formatPk(user, destinationSkuId);
    var sequenceNumber = sourceUnit.getSequenceNumber();
    var movedUnit = new InventoryUnitItem();
    movedUnit.setPk(destinationPk);
    movedUnit.setSk(InventoryUnitItem.formatSk(sequenceNumber));
    movedUnit.setSequenceNumber(sequenceNumber);
    movedUnit.setGame(sourceUnit.getGame());
    movedUnit.setGsi3pk("USER#" + user + "#UNITS#" + sourceUnit.getGame());
    movedUnit.setStatus("in_stock");
    movedUnit.setCreatedAt(sourceUnit.getCreatedAt());
    movedUnit.setUpdatedAt(now);
    movedUnit.setPhotos(sourceUnit.getPhotos());

    var name = sourceSku.getName();
    var audit =
        TableSchema.fromBean(InventoryAuditItem.class)
            .itemToMap(
                InventoryAuditItem.create(
                    user,
                    ulidGenerator.generate(),
                    "adjustment",
                    sourceSku.getSkuId(),
                    sequenceNumber,
                    null,
                    "in_stock",
                    "in_stock",
                    destinationSkuId,
                    now),
                true);

    var sourceValues =
        Map.of(
            ":dirty", AttributeValue.builder().bool(true).build(),
            ":dirtyPk", AttributeValue.builder().s("USER#" + user + "#DIRTY").build(),
            ":one", AttributeValue.builder().n("1").build());
    var destinationValues = new HashMap<String, AttributeValue>();
    destinationValues.put(":dirty", AttributeValue.builder().bool(true).build());
    destinationValues.put(
        ":dirtyPk", AttributeValue.builder().s("USER#" + user + "#DIRTY").build());
    destinationValues.put(":one", AttributeValue.builder().n("1").build());
    destinationValues.put(":game", AttributeValue.builder().s(sourceSku.getGame()).build());
    destinationValues.put(
        ":externalId", AttributeValue.builder().s(sourceSku.getExternalId()).build());
    destinationValues.put(":name", AttributeValue.builder().s(name).build());
    destinationValues.put(":finish", AttributeValue.builder().s(sourceSku.getFinish()).build());
    destinationValues.put(":condition", AttributeValue.builder().s(condition).build());
    destinationValues.put(":skuId", AttributeValue.builder().s(destinationSkuId).build());
    destinationValues.put(
        ":browsePk", AttributeValue.builder().s("USER#" + user + "#SKUS").build());
    destinationValues.put(
        ":browseSk",
        AttributeValue.builder()
            .s(
                "GAME#"
                    + sourceSku.getGame()
                    + "#NAME#"
                    + name.toLowerCase(Locale.ROOT)
                    + "#"
                    + destinationSkuId)
            .build());
    destinationValues.put(
        ":dirtySk", AttributeValue.builder().s("SKU#" + destinationSkuId).build());
    var destinationExpression =
        new StringBuilder(
            "SET sku_id = if_not_exists(sku_id, :skuId), "
                + "game = if_not_exists(game, :game), "
                + "external_id = if_not_exists(external_id, :externalId), "
                + "#name = if_not_exists(#name, :name), "
                + "finish = if_not_exists(finish, :finish), "
                + "#condition = if_not_exists(#condition, :condition), "
                + "gsi1pk = :dirtyPk, gsi1sk = :dirtySk, gsi2pk = :browsePk, "
                + "gsi2sk = if_not_exists(gsi2sk, :browseSk), dirty = :dirty");
    addDestinationField(
        destinationExpression, destinationValues, "set_code", "setCode", sourceSku.getSetCode());
    addDestinationField(
        destinationExpression, destinationValues, "set_name", "setName", sourceSku.getSetName());
    addDestinationField(
        destinationExpression,
        destinationValues,
        "collector_number",
        "collectorNumber",
        sourceSku.getCollectorNumber());
    addDestinationField(
        destinationExpression,
        destinationValues,
        "suggested_price",
        "suggestedPrice",
        sourceSku.getSuggestedPrice());
    addDestinationField(
        destinationExpression,
        destinationValues,
        "fetchtcg_card_id",
        "fetchCardId",
        sourceSku.getFetchtcgCardId());
    destinationExpression.append(", fetchtcg_set_id = if_not_exists(fetchtcg_set_id, :fetchSetId)");
    destinationValues.put(
        ":fetchSetId",
        AttributeValue.builder().n(Integer.toString(sourceSku.getFetchtcgSetId())).build());
    destinationExpression.append(" ADD #version :one");

    dynamoDbClient.transactWriteItems(
        TransactWriteItemsRequest.builder()
            .transactItems(
                TransactWriteItem.builder()
                    .delete(
                        Delete.builder()
                            .tableName(tableName)
                            .key(
                                Map.of(
                                    "pk", AttributeValue.builder().s(sourcePk).build(),
                                    "sk", AttributeValue.builder().s(sourceUnit.getSk()).build()))
                            .conditionExpression("#status = :inStock")
                            .expressionAttributeNames(Map.of("#status", "status"))
                            .expressionAttributeValues(
                                Map.of(":inStock", AttributeValue.builder().s("in_stock").build()))
                            .build())
                    .build(),
                TransactWriteItem.builder()
                    .put(
                        Put.builder()
                            .tableName(tableName)
                            .item(
                                TableSchema.fromBean(InventoryUnitItem.class)
                                    .itemToMap(movedUnit, true))
                            .conditionExpression("attribute_not_exists(pk)")
                            .build())
                    .build(),
                TransactWriteItem.builder()
                    .update(
                        Update.builder()
                            .tableName(tableName)
                            .key(
                                Map.of(
                                    "pk", AttributeValue.builder().s(sourcePk).build(),
                                    "sk", AttributeValue.builder().s("SKU").build()))
                            .updateExpression(
                                "SET dirty = :dirty, gsi1pk = :dirtyPk ADD #version :one")
                            .conditionExpression("attribute_exists(pk)")
                            .expressionAttributeNames(Map.of("#version", "version"))
                            .expressionAttributeValues(sourceValues)
                            .build())
                    .build(),
                TransactWriteItem.builder()
                    .update(
                        Update.builder()
                            .tableName(tableName)
                            .key(
                                Map.of(
                                    "pk", AttributeValue.builder().s(destinationPk).build(),
                                    "sk", AttributeValue.builder().s("SKU").build()))
                            .updateExpression(destinationExpression.toString())
                            .expressionAttributeNames(
                                Map.of(
                                    "#version",
                                    "version",
                                    "#name",
                                    "name",
                                    "#condition",
                                    "condition"))
                            .expressionAttributeValues(destinationValues)
                            .build())
                    .build(),
                TransactWriteItem.builder()
                    .put(Put.builder().tableName(tableName).item(audit).build())
                    .build())
            .build());
  }

  private static void addDestinationField(
      StringBuilder expression,
      Map<String, AttributeValue> values,
      String attribute,
      String valueName,
      @Nullable String value) {
    if (value != null) {
      expression
          .append(", ")
          .append(attribute)
          .append(" = if_not_exists(")
          .append(attribute)
          .append(", :")
          .append(valueName)
          .append(")");
      values.put(":" + valueName, AttributeValue.builder().s(value).build());
    }
  }
}
