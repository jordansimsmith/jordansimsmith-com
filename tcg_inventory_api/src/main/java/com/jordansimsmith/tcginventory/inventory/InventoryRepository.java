package com.jordansimsmith.tcginventory.inventory;

import com.jordansimsmith.tcginventory.AuditItem;
import com.jordansimsmith.tcginventory.CardIdentity;
import com.jordansimsmith.tcginventory.Condition;
import com.jordansimsmith.tcginventory.SkuIds;
import com.jordansimsmith.tcginventory.TcgInventoryTable;
import com.jordansimsmith.time.Clock;
import com.jordansimsmith.ulid.UlidGenerator;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryEnhancedRequest;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.Delete;
import software.amazon.awssdk.services.dynamodb.model.Put;
import software.amazon.awssdk.services.dynamodb.model.ReturnValuesOnConditionCheckFailure;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;
import software.amazon.awssdk.services.dynamodb.model.Update;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;

public class InventoryRepository {
  private static final Logger LOGGER = LoggerFactory.getLogger(InventoryRepository.class);

  private static final int MAX_TRANSACT_ITEMS = 100;

  private final DynamoDbTable<UnitItem> unitTable;
  private final DynamoDbClient dynamoDbClient;
  private final Clock clock;
  private final UlidGenerator ulidGenerator;

  public InventoryRepository(
      DynamoDbTable<UnitItem> unitTable,
      DynamoDbClient dynamoDbClient,
      Clock clock,
      UlidGenerator ulidGenerator) {
    this.unitTable = unitTable;
    this.dynamoDbClient = dynamoDbClient;
    this.clock = clock;
    this.ulidGenerator = ulidGenerator;
  }

  public List<UnitItem> findUnits(String user, String skuId) {
    var request =
        QueryEnhancedRequest.builder()
            .queryConditional(
                QueryConditional.sortBeginsWith(
                    Key.builder()
                        .partitionValue(SkuItem.formatPk(user, skuId))
                        .sortValue(UnitItem.UNIT_PREFIX)
                        .build()))
            .build();

    return unitTable.query(request).stream().flatMap(page -> page.items().stream()).toList();
  }

  public List<UnitItem> findUnitsToAllocate(
      String user, String skuId, String orderId, int quantity) {
    var results = new ArrayList<UnitItem>();
    for (var item : findUnits(user, skuId)) {
      // units already reserved for this order were allocated by a run that died before
      // writing the order item; reclaiming them keeps retries convergent
      var reclaimed = "reserved".equals(item.getStatus()) && orderId.equals(item.getOrderId());
      if ("in_stock".equals(item.getStatus()) || reclaimed) {
        results.add(item);
        if (results.size() >= quantity) {
          break;
        }
      }
    }
    return results;
  }

  public void removeUnit(String user, String skuId, int sequenceNumber, @Nullable String reason) {
    var auditAttributes = new HashMap<String, AttributeValue>();
    auditAttributes.put(SkuItem.SKU_ID, AttributeValue.builder().s(skuId).build());
    auditAttributes.put(
        UnitItem.SEQUENCE_NUMBER,
        AttributeValue.builder().n(String.valueOf(sequenceNumber)).build());
    if (reason != null && !reason.isEmpty()) {
      auditAttributes.put("decision_reason", AttributeValue.builder().s(reason).build());
    }

    executeChunked(
        List.of(
            buildSkuDirtyUpdate(user, skuId),
            buildUnitRemoveUpdate(user, skuId, sequenceNumber),
            buildAuditPut(user, "adjustment", auditAttributes)));
  }

  // one transaction across both SKU partitions: the unit moves keeping its sequence number and
  // photos, the source SKU is dirtied, and the target SKU record is created or refreshed
  public String updateUnitCondition(
      String user, SkuItem skuItem, UnitItem unitItem, String condition) {
    var identity =
        new CardIdentity(skuItem.getGame(), skuItem.getExternalSource(), skuItem.getExternalId());
    var targetSkuId = SkuIds.format(identity, skuItem.getFinish(), Condition.valueOf(condition));
    var targetSku =
        SkuItem.create(
            user,
            targetSkuId,
            identity.game(),
            identity.externalSource(),
            identity.externalId(),
            skuItem.getFinish(),
            condition,
            skuItem.getName(),
            skuItem.getSetCode(),
            skuItem.getSetName(),
            skuItem.getCollectorNumber(),
            skuItem.getFetchtcgCardId(),
            skuItem.getSuggestedPrice());

    var movedUnit =
        UnitItem.create(
            user,
            identity.game(),
            targetSkuId,
            unitItem.getSequenceNumber(),
            "in_stock",
            unitItem.getImportId(),
            unitItem.getCreatedAt());
    if (unitItem.getPhotos() != null && !unitItem.getPhotos().isEmpty()) {
      movedUnit.setPhotos(unitItem.getPhotos());
    }

    executeChunked(
        List.of(
            buildUnitDelete(user, skuItem.getSkuId(), unitItem.getSequenceNumber()),
            buildUnitPut(movedUnit),
            buildSkuDirtyUpdate(user, skuItem.getSkuId()),
            buildSkuUpsert(targetSku),
            buildAuditPut(
                user,
                "adjustment",
                Map.of(
                    SkuItem.SKU_ID,
                    AttributeValue.builder().s(skuItem.getSkuId()).build(),
                    UnitItem.SEQUENCE_NUMBER,
                    AttributeValue.builder()
                        .n(String.valueOf(unitItem.getSequenceNumber()))
                        .build()))));

    return targetSkuId;
  }

  public int allocateSequenceRange(String user, String game, int count) {
    var response =
        dynamoDbClient.updateItem(
            UpdateItemRequest.builder()
                .tableName(TcgInventoryTable.TABLE_NAME)
                .key(
                    Map.of(
                        SkuItem.PK,
                        AttributeValue.builder().s(SkuItem.formatUserPk(user)).build(),
                        SkuItem.SK,
                        AttributeValue.builder().s(SequenceCounterItem.formatSk(game)).build()))
                .updateExpression(
                    "SET "
                        + SequenceCounterItem.GAME
                        + " = :game ADD "
                        + SequenceCounterItem.NEXT_SEQUENCE_NUMBER
                        + " :n")
                .expressionAttributeValues(
                    Map.of(
                        ":game",
                        AttributeValue.builder().s(game).build(),
                        ":n",
                        AttributeValue.builder().n(String.valueOf(count)).build()))
                .returnValues("ALL_NEW")
                .build());

    int newValue =
        Integer.parseInt(response.attributes().get(SequenceCounterItem.NEXT_SEQUENCE_NUMBER).n());
    return newValue - count;
  }

  // deliberately a single transaction rather than a chunked sequence: a replayed chunk fails its
  // unit-exists condition and the whole transaction cancels atomically into a no-op
  public void confirmImportSku(
      String user, String importId, SkuItem skuSeed, List<UnitItem> units) {
    var transactItems = new ArrayList<TransactWriteItem>();
    for (var unit : units) {
      transactItems.add(
          TransactWriteItem.builder()
              .put(
                  Put.builder()
                      .tableName(TcgInventoryTable.TABLE_NAME)
                      .item(unitTable.tableSchema().itemToMap(unit, true))
                      .conditionExpression("attribute_not_exists(pk)")
                      .returnValuesOnConditionCheckFailure(ReturnValuesOnConditionCheckFailure.NONE)
                      .build())
              .build());
    }
    transactItems.add(buildSkuUpsert(skuSeed));
    transactItems.add(
        buildAuditPut(
            user,
            "import_confirm",
            Map.of(
                "import_id",
                AttributeValue.builder().s(importId).build(),
                SkuItem.SKU_ID,
                AttributeValue.builder().s(skuSeed.getSkuId()).build())));

    try {
      dynamoDbClient.transactWriteItems(
          TransactWriteItemsRequest.builder().transactItems(transactItems).build());
    } catch (TransactionCanceledException e) {
      LOGGER.info("transaction cancelled for SKU chunk {} (likely replay)", skuSeed.getSkuId());
    }
  }

  public void executeChunked(List<TransactWriteItem> transactItems) {
    for (int start = 0; start < transactItems.size(); start += MAX_TRANSACT_ITEMS) {
      var chunk =
          transactItems.subList(start, Math.min(start + MAX_TRANSACT_ITEMS, transactItems.size()));
      dynamoDbClient.transactWriteItems(
          TransactWriteItemsRequest.builder().transactItems(chunk).build());
    }
  }

  public TransactWriteItem buildUnitReserveUpdate(
      String user, String skuId, int sequenceNumber, String orderId) {
    var skuPk = SkuItem.formatPk(user, skuId);
    var unitSk = UnitItem.formatSk(sequenceNumber);

    return TransactWriteItem.builder()
        .update(
            Update.builder()
                .tableName(TcgInventoryTable.TABLE_NAME)
                .key(
                    Map.of(
                        SkuItem.PK, AttributeValue.builder().s(skuPk).build(),
                        SkuItem.SK, AttributeValue.builder().s(unitSk).build()))
                .updateExpression(
                    "SET #status = :reserved, "
                        + "order_id"
                        + " = :orderId, "
                        + UnitItem.UPDATED_AT
                        + " = :now")
                .conditionExpression("#status = :inStock")
                .expressionAttributeNames(Map.of("#status", UnitItem.STATUS))
                .expressionAttributeValues(
                    Map.of(
                        ":reserved", AttributeValue.builder().s("reserved").build(),
                        ":inStock", AttributeValue.builder().s("in_stock").build(),
                        ":orderId", AttributeValue.builder().s(orderId).build(),
                        ":now",
                            AttributeValue.builder()
                                .n(String.valueOf(clock.now().getEpochSecond()))
                                .build()))
                .build())
        .build();
  }

  public TransactWriteItem buildUnitSellUpdate(String user, String skuId, int sequenceNumber) {
    var skuPk = SkuItem.formatPk(user, skuId);
    var unitSk = UnitItem.formatSk(sequenceNumber);

    return TransactWriteItem.builder()
        .update(
            Update.builder()
                .tableName(TcgInventoryTable.TABLE_NAME)
                .key(
                    Map.of(
                        SkuItem.PK, AttributeValue.builder().s(skuPk).build(),
                        SkuItem.SK, AttributeValue.builder().s(unitSk).build()))
                .updateExpression("SET #status = :sold, " + UnitItem.UPDATED_AT + " = :now")
                .conditionExpression("#status IN (:reserved, :sold)")
                .expressionAttributeNames(Map.of("#status", UnitItem.STATUS))
                .expressionAttributeValues(
                    Map.of(
                        ":sold", AttributeValue.builder().s("sold").build(),
                        ":reserved", AttributeValue.builder().s("reserved").build(),
                        ":now",
                            AttributeValue.builder()
                                .n(String.valueOf(clock.now().getEpochSecond()))
                                .build()))
                .build())
        .build();
  }

  public TransactWriteItem buildUnitReleaseUpdate(String user, String skuId, int sequenceNumber) {
    var skuPk = SkuItem.formatPk(user, skuId);
    var unitSk = UnitItem.formatSk(sequenceNumber);

    return TransactWriteItem.builder()
        .update(
            Update.builder()
                .tableName(TcgInventoryTable.TABLE_NAME)
                .key(
                    Map.of(
                        SkuItem.PK, AttributeValue.builder().s(skuPk).build(),
                        SkuItem.SK, AttributeValue.builder().s(unitSk).build()))
                .updateExpression(
                    "SET #status = :inStock, "
                        + UnitItem.UPDATED_AT
                        + " = :now REMOVE "
                        + "order_id")
                .conditionExpression("#status IN (:reserved, :inStock)")
                .expressionAttributeNames(Map.of("#status", UnitItem.STATUS))
                .expressionAttributeValues(
                    Map.of(
                        ":inStock", AttributeValue.builder().s("in_stock").build(),
                        ":reserved", AttributeValue.builder().s("reserved").build(),
                        ":now",
                            AttributeValue.builder()
                                .n(String.valueOf(clock.now().getEpochSecond()))
                                .build()))
                .build())
        .build();
  }

  public TransactWriteItem buildUnitRemoveUpdate(String user, String skuId, int sequenceNumber) {
    var skuPk = SkuItem.formatPk(user, skuId);
    var unitSk = UnitItem.formatSk(sequenceNumber);

    return TransactWriteItem.builder()
        .update(
            Update.builder()
                .tableName(TcgInventoryTable.TABLE_NAME)
                .key(
                    Map.of(
                        SkuItem.PK, AttributeValue.builder().s(skuPk).build(),
                        SkuItem.SK, AttributeValue.builder().s(unitSk).build()))
                .updateExpression("SET #status = :removed, " + UnitItem.UPDATED_AT + " = :now")
                .conditionExpression("#status = :inStock")
                .expressionAttributeNames(Map.of("#status", UnitItem.STATUS))
                .expressionAttributeValues(
                    Map.of(
                        ":removed", AttributeValue.builder().s("removed").build(),
                        ":inStock", AttributeValue.builder().s("in_stock").build(),
                        ":now",
                            AttributeValue.builder()
                                .n(String.valueOf(clock.now().getEpochSecond()))
                                .build()))
                .build())
        .build();
  }

  public TransactWriteItem buildUnitDelete(String user, String skuId, int sequenceNumber) {
    var skuPk = SkuItem.formatPk(user, skuId);
    var unitSk = UnitItem.formatSk(sequenceNumber);

    return TransactWriteItem.builder()
        .delete(
            Delete.builder()
                .tableName(TcgInventoryTable.TABLE_NAME)
                .key(
                    Map.of(
                        SkuItem.PK, AttributeValue.builder().s(skuPk).build(),
                        SkuItem.SK, AttributeValue.builder().s(unitSk).build()))
                .conditionExpression("#status = :inStock")
                .expressionAttributeNames(Map.of("#status", UnitItem.STATUS))
                .expressionAttributeValues(
                    Map.of(":inStock", AttributeValue.builder().s("in_stock").build()))
                .build())
        .build();
  }

  public TransactWriteItem buildUnitPut(UnitItem unitItem) {
    return TransactWriteItem.builder()
        .put(
            Put.builder()
                .tableName(TcgInventoryTable.TABLE_NAME)
                .item(unitTable.tableSchema().itemToMap(unitItem, true))
                .build())
        .build();
  }

  public TransactWriteItem buildSkuUpsert(SkuItem skuSeed) {
    var expression =
        new StringBuilder(
            "ADD "
                + SkuItem.VERSION
                + " :one SET "
                + SkuItem.SKU_ID
                + " = :skuId, "
                + SkuItem.GAME
                + " = :game, "
                + SkuItem.EXTERNAL_SOURCE
                + " = :externalSource, "
                + SkuItem.EXTERNAL_ID
                + " = :externalId, "
                + "#finish = :finish, "
                + "#condition = :condition, "
                + "#name = :cardName, "
                + SkuItem.SET_CODE
                + " = :setCode, "
                + SkuItem.SET_NAME
                + " = :setName, "
                + SkuItem.COLLECTOR_NUMBER
                + " = :collectorNumber, "
                + SkuItem.DIRTY
                + " = :dirty, "
                + SkuItem.GSI1PK
                + " = :gsi1pk, "
                + SkuItem.GSI1SK
                + " = :gsi1sk, "
                + SkuItem.GSI2PK
                + " = :gsi2pk, "
                + SkuItem.GSI2SK
                + " = :gsi2sk");

    var values = new HashMap<String, AttributeValue>();
    values.put(":one", AttributeValue.builder().n("1").build());
    values.put(":skuId", AttributeValue.builder().s(skuSeed.getSkuId()).build());
    values.put(":game", AttributeValue.builder().s(skuSeed.getGame()).build());
    values.put(":externalSource", AttributeValue.builder().s(skuSeed.getExternalSource()).build());
    values.put(":externalId", AttributeValue.builder().s(skuSeed.getExternalId()).build());
    values.put(":finish", AttributeValue.builder().s(skuSeed.getFinish()).build());
    values.put(":condition", AttributeValue.builder().s(skuSeed.getCondition()).build());
    values.put(":cardName", AttributeValue.builder().s(skuSeed.getName()).build());
    values.put(":setCode", AttributeValue.builder().s(skuSeed.getSetCode()).build());
    values.put(":setName", AttributeValue.builder().s(skuSeed.getSetName()).build());
    values.put(
        ":collectorNumber", AttributeValue.builder().s(skuSeed.getCollectorNumber()).build());
    values.put(":dirty", AttributeValue.builder().bool(true).build());
    values.put(":gsi1pk", AttributeValue.builder().s(skuSeed.getGsi1pk()).build());
    values.put(":gsi1sk", AttributeValue.builder().s(skuSeed.getGsi1sk()).build());
    values.put(":gsi2pk", AttributeValue.builder().s(skuSeed.getGsi2pk()).build());
    values.put(":gsi2sk", AttributeValue.builder().s(skuSeed.getGsi2sk()).build());

    if (skuSeed.getSuggestedPrice() != null) {
      expression.append(", " + SkuItem.SUGGESTED_PRICE + " = :suggestedPrice");
      values.put(
          ":suggestedPrice", AttributeValue.builder().s(skuSeed.getSuggestedPrice()).build());
    }
    if (skuSeed.getFetchtcgCardId() != null) {
      expression.append(", " + SkuItem.FETCHTCG_CARD_ID + " = :fetchtcgCardId");
      values.put(
          ":fetchtcgCardId", AttributeValue.builder().s(skuSeed.getFetchtcgCardId()).build());
    }
    if (skuSeed.getFetchtcgSetId() != null) {
      expression.append(", " + SkuItem.FETCHTCG_SET_ID + " = :fetchtcgSetId");
      values.put(
          ":fetchtcgSetId",
          AttributeValue.builder().n(String.valueOf(skuSeed.getFetchtcgSetId())).build());
    }

    return TransactWriteItem.builder()
        .update(
            Update.builder()
                .tableName(TcgInventoryTable.TABLE_NAME)
                .key(
                    Map.of(
                        SkuItem.PK, AttributeValue.builder().s(skuSeed.getPk()).build(),
                        SkuItem.SK, AttributeValue.builder().s(skuSeed.getSk()).build()))
                .updateExpression(expression.toString())
                .expressionAttributeNames(
                    Map.of(
                        "#finish", SkuItem.FINISH,
                        "#condition", SkuItem.CONDITION,
                        "#name", SkuItem.NAME))
                .expressionAttributeValues(values)
                .build())
        .build();
  }

  public TransactWriteItem buildSkuDirtyUpdate(String user, String skuId) {
    var skuPk = SkuItem.formatPk(user, skuId);

    return TransactWriteItem.builder()
        .update(
            Update.builder()
                .tableName(TcgInventoryTable.TABLE_NAME)
                .key(
                    Map.of(
                        SkuItem.PK, AttributeValue.builder().s(skuPk).build(),
                        SkuItem.SK, AttributeValue.builder().s(SkuItem.formatSk()).build()))
                .updateExpression(
                    "ADD "
                        + SkuItem.VERSION
                        + " :one SET "
                        + SkuItem.DIRTY
                        + " = :dirty, "
                        + SkuItem.GSI1PK
                        + " = :gsi1pk")
                .expressionAttributeValues(
                    Map.of(
                        ":one", AttributeValue.builder().n("1").build(),
                        ":dirty", AttributeValue.builder().bool(true).build(),
                        ":gsi1pk", AttributeValue.builder().s(SkuItem.formatGsi1pk(user)).build()))
                .build())
        .build();
  }

  public TransactWriteItem buildSkuVersionBump(String user, String skuId) {
    var skuPk = SkuItem.formatPk(user, skuId);

    return TransactWriteItem.builder()
        .update(
            Update.builder()
                .tableName(TcgInventoryTable.TABLE_NAME)
                .key(
                    Map.of(
                        SkuItem.PK, AttributeValue.builder().s(skuPk).build(),
                        SkuItem.SK, AttributeValue.builder().s(SkuItem.formatSk()).build()))
                .updateExpression("ADD " + SkuItem.VERSION + " :one")
                .expressionAttributeValues(Map.of(":one", AttributeValue.builder().n("1").build()))
                .build())
        .build();
  }

  public TransactWriteItem buildAuditPut(
      String user, String eventType, Map<String, AttributeValue> attributes) {
    var auditItem = new HashMap<>(attributes);
    auditItem.put(AuditItem.PK, AttributeValue.builder().s(AuditItem.formatPk(user)).build());
    auditItem.put(AuditItem.SK, AttributeValue.builder().s(ulidGenerator.generate()).build());
    auditItem.put(AuditItem.EVENT_TYPE, AttributeValue.builder().s(eventType).build());
    auditItem.put(
        AuditItem.CREATED_AT,
        AttributeValue.builder().n(String.valueOf(clock.now().getEpochSecond())).build());

    return TransactWriteItem.builder()
        .put(Put.builder().tableName(TcgInventoryTable.TABLE_NAME).item(auditItem).build())
        .build();
  }
}
