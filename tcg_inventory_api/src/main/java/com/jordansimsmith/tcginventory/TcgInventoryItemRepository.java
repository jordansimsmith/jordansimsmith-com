package com.jordansimsmith.tcginventory;

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

public class TcgInventoryItemRepository {
  private static final Logger LOGGER = LoggerFactory.getLogger(TcgInventoryItemRepository.class);

  private static final int MAX_TRANSACT_ITEMS = 100;

  public record SkuUnits(String skuId, List<Integer> sequenceNumbers) {}

  private final DynamoDbTable<TcgInventoryItem> tcgInventoryTable;
  private final DynamoDbClient dynamoDbClient;
  private final Clock clock;
  private final UlidGenerator ulidGenerator;

  public TcgInventoryItemRepository(
      DynamoDbTable<TcgInventoryItem> tcgInventoryTable,
      DynamoDbClient dynamoDbClient,
      Clock clock,
      UlidGenerator ulidGenerator) {
    this.tcgInventoryTable = tcgInventoryTable;
    this.dynamoDbClient = dynamoDbClient;
    this.clock = clock;
    this.ulidGenerator = ulidGenerator;
  }

  public List<TcgInventoryItem> findUnits(String user, String skuId) {
    var request =
        QueryEnhancedRequest.builder()
            .queryConditional(
                QueryConditional.sortBeginsWith(
                    Key.builder()
                        .partitionValue(TcgInventoryItem.formatSkuPk(user, skuId))
                        .sortValue(TcgInventoryItem.UNIT_PREFIX)
                        .build()))
            .build();

    return tcgInventoryTable.query(request).stream()
        .flatMap(page -> page.items().stream())
        .toList();
  }

  public List<TcgInventoryItem> findUnitsToAllocate(
      String user, String skuId, String orderId, int quantity) {
    var results = new ArrayList<TcgInventoryItem>();
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

  // the conditional order put and the audit entry ride in the final chunk, so the order item's
  // existence marks the whole reservation complete; a run that dies earlier leaves the offer
  // untracked and the next run reclaims the units it already reserved
  public void reserveOrder(
      String user, TcgInventoryItem orderItem, List<SkuUnits> newReservations) {
    var transactItems = new ArrayList<TransactWriteItem>();
    for (var reservation : newReservations) {
      transactItems.add(buildSkuDirtyUpdate(user, reservation.skuId()));
      for (var sequenceNumber : reservation.sequenceNumbers()) {
        transactItems.add(
            buildUnitReserveUpdate(
                user, reservation.skuId(), sequenceNumber, orderItem.getOrderId()));
      }
    }

    transactItems.add(
        TransactWriteItem.builder()
            .put(
                Put.builder()
                    .tableName(TcgInventoryItem.TABLE_NAME)
                    .item(tcgInventoryTable.tableSchema().itemToMap(orderItem, true))
                    .conditionExpression("attribute_not_exists(pk)")
                    .build())
            .build());
    transactItems.add(
        buildAuditPut(
            user,
            "reserve",
            Map.of(
                TcgInventoryItem.ORDER_ID,
                AttributeValue.builder().s(orderItem.getOrderId()).build())));

    executeChunked(transactItems);
  }

  // the conditional order flip and the audit entry ride in the final chunk; a partially applied
  // confirm leaves the order to_pick so the client can retry, and the unit condition tolerates
  // units the failed attempt already sold
  public void sellOrder(String user, String orderId, List<SkuUnits> sales) {
    var transactItems = new ArrayList<TransactWriteItem>();
    for (var sale : sales) {
      transactItems.add(buildSkuVersionBump(user, sale.skuId()));
      for (var sequenceNumber : sale.sequenceNumbers()) {
        transactItems.add(buildUnitSellUpdate(user, sale.skuId(), sequenceNumber));
      }
    }

    transactItems.add(buildOrderFulfilledUpdate(user, orderId));
    transactItems.add(
        buildAuditPut(
            user,
            "sell",
            Map.of(TcgInventoryItem.ORDER_ID, AttributeValue.builder().s(orderId).build())));

    executeChunked(transactItems);
  }

  public void removeUnit(String user, String skuId, int sequenceNumber, @Nullable String reason) {
    var auditAttributes = new HashMap<String, AttributeValue>();
    auditAttributes.put(TcgInventoryItem.SKU_ID, AttributeValue.builder().s(skuId).build());
    auditAttributes.put(
        TcgInventoryItem.SEQUENCE_NUMBER,
        AttributeValue.builder().n(String.valueOf(sequenceNumber)).build());
    if (reason != null && !reason.isEmpty()) {
      auditAttributes.put(
          TcgInventoryItem.DECISION_REASON, AttributeValue.builder().s(reason).build());
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
      String user, TcgInventoryItem skuItem, TcgInventoryItem unitItem, String condition) {
    var targetSkuId = skuItem.getScryfallId() + "#" + skuItem.getFinish() + "#" + condition;
    var targetSku =
        TcgInventoryItem.createSku(
            user,
            targetSkuId,
            skuItem.getScryfallId(),
            skuItem.getFinish(),
            condition,
            skuItem.getName(),
            skuItem.getSetCode(),
            skuItem.getSetName(),
            skuItem.getCollectorNumber(),
            skuItem.getFetchtcgCardId(),
            skuItem.getSuggestedPrice());

    var movedUnit =
        TcgInventoryItem.createUnit(
            user,
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
                    TcgInventoryItem.SKU_ID,
                    AttributeValue.builder().s(skuItem.getSkuId()).build(),
                    TcgInventoryItem.SEQUENCE_NUMBER,
                    AttributeValue.builder()
                        .n(String.valueOf(unitItem.getSequenceNumber()))
                        .build()))));

    return targetSkuId;
  }

  public int allocateSequenceRange(String user, int count) {
    var response =
        dynamoDbClient.updateItem(
            UpdateItemRequest.builder()
                .tableName(TcgInventoryItem.TABLE_NAME)
                .key(
                    Map.of(
                        TcgInventoryItem.PK,
                        AttributeValue.builder().s(TcgInventoryItem.formatUserPk(user)).build(),
                        TcgInventoryItem.SK,
                        AttributeValue.builder().s(TcgInventoryItem.formatCounterSk()).build()))
                .updateExpression("ADD " + TcgInventoryItem.NEXT_SEQUENCE_NUMBER + " :n")
                .expressionAttributeValues(
                    Map.of(":n", AttributeValue.builder().n(String.valueOf(count)).build()))
                .returnValues("ALL_NEW")
                .build());

    int newValue =
        Integer.parseInt(response.attributes().get(TcgInventoryItem.NEXT_SEQUENCE_NUMBER).n());
    return newValue - count;
  }

  // deliberately a single transaction rather than a chunked sequence: a replayed chunk fails its
  // unit-exists condition and the whole transaction cancels atomically into a no-op
  public void confirmImportSku(
      String user, String importId, TcgInventoryItem skuSeed, List<TcgInventoryItem> units) {
    var transactItems = new ArrayList<TransactWriteItem>();
    for (var unit : units) {
      transactItems.add(
          TransactWriteItem.builder()
              .put(
                  Put.builder()
                      .tableName(TcgInventoryItem.TABLE_NAME)
                      .item(tcgInventoryTable.tableSchema().itemToMap(unit, true))
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
                TcgInventoryItem.IMPORT_ID,
                AttributeValue.builder().s(importId).build(),
                TcgInventoryItem.SKU_ID,
                AttributeValue.builder().s(skuSeed.getSkuId()).build())));

    try {
      dynamoDbClient.transactWriteItems(
          TransactWriteItemsRequest.builder().transactItems(transactItems).build());
    } catch (TransactionCanceledException e) {
      LOGGER.info("transaction cancelled for SKU chunk {} (likely replay)", skuSeed.getSkuId());
    }
  }

  private void executeChunked(List<TransactWriteItem> transactItems) {
    for (int start = 0; start < transactItems.size(); start += MAX_TRANSACT_ITEMS) {
      var chunk =
          transactItems.subList(start, Math.min(start + MAX_TRANSACT_ITEMS, transactItems.size()));
      dynamoDbClient.transactWriteItems(
          TransactWriteItemsRequest.builder().transactItems(chunk).build());
    }
  }

  private TransactWriteItem buildUnitReserveUpdate(
      String user, String skuId, int sequenceNumber, String orderId) {
    var skuPk = TcgInventoryItem.formatSkuPk(user, skuId);
    var unitSk = TcgInventoryItem.formatUnitSk(sequenceNumber);

    return TransactWriteItem.builder()
        .update(
            Update.builder()
                .tableName(TcgInventoryItem.TABLE_NAME)
                .key(
                    Map.of(
                        TcgInventoryItem.PK, AttributeValue.builder().s(skuPk).build(),
                        TcgInventoryItem.SK, AttributeValue.builder().s(unitSk).build()))
                .updateExpression(
                    "SET #status = :reserved, "
                        + TcgInventoryItem.ORDER_ID
                        + " = :orderId, "
                        + TcgInventoryItem.UPDATED_AT
                        + " = :now")
                .conditionExpression("#status = :inStock")
                .expressionAttributeNames(Map.of("#status", TcgInventoryItem.STATUS))
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

  private TransactWriteItem buildUnitSellUpdate(String user, String skuId, int sequenceNumber) {
    var skuPk = TcgInventoryItem.formatSkuPk(user, skuId);
    var unitSk = TcgInventoryItem.formatUnitSk(sequenceNumber);

    return TransactWriteItem.builder()
        .update(
            Update.builder()
                .tableName(TcgInventoryItem.TABLE_NAME)
                .key(
                    Map.of(
                        TcgInventoryItem.PK, AttributeValue.builder().s(skuPk).build(),
                        TcgInventoryItem.SK, AttributeValue.builder().s(unitSk).build()))
                .updateExpression("SET #status = :sold, " + TcgInventoryItem.UPDATED_AT + " = :now")
                .conditionExpression("#status IN (:reserved, :sold)")
                .expressionAttributeNames(Map.of("#status", TcgInventoryItem.STATUS))
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

  private TransactWriteItem buildUnitRemoveUpdate(String user, String skuId, int sequenceNumber) {
    var skuPk = TcgInventoryItem.formatSkuPk(user, skuId);
    var unitSk = TcgInventoryItem.formatUnitSk(sequenceNumber);

    return TransactWriteItem.builder()
        .update(
            Update.builder()
                .tableName(TcgInventoryItem.TABLE_NAME)
                .key(
                    Map.of(
                        TcgInventoryItem.PK, AttributeValue.builder().s(skuPk).build(),
                        TcgInventoryItem.SK, AttributeValue.builder().s(unitSk).build()))
                .updateExpression(
                    "SET #status = :removed, " + TcgInventoryItem.UPDATED_AT + " = :now")
                .conditionExpression("#status = :inStock")
                .expressionAttributeNames(Map.of("#status", TcgInventoryItem.STATUS))
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

  private TransactWriteItem buildUnitDelete(String user, String skuId, int sequenceNumber) {
    var skuPk = TcgInventoryItem.formatSkuPk(user, skuId);
    var unitSk = TcgInventoryItem.formatUnitSk(sequenceNumber);

    return TransactWriteItem.builder()
        .delete(
            Delete.builder()
                .tableName(TcgInventoryItem.TABLE_NAME)
                .key(
                    Map.of(
                        TcgInventoryItem.PK, AttributeValue.builder().s(skuPk).build(),
                        TcgInventoryItem.SK, AttributeValue.builder().s(unitSk).build()))
                .conditionExpression("#status = :inStock")
                .expressionAttributeNames(Map.of("#status", TcgInventoryItem.STATUS))
                .expressionAttributeValues(
                    Map.of(":inStock", AttributeValue.builder().s("in_stock").build()))
                .build())
        .build();
  }

  private TransactWriteItem buildUnitPut(TcgInventoryItem unitItem) {
    return TransactWriteItem.builder()
        .put(
            Put.builder()
                .tableName(TcgInventoryItem.TABLE_NAME)
                .item(tcgInventoryTable.tableSchema().itemToMap(unitItem, true))
                .build())
        .build();
  }

  private TransactWriteItem buildSkuUpsert(TcgInventoryItem skuSeed) {
    var expression =
        new StringBuilder(
            "ADD "
                + TcgInventoryItem.VERSION
                + " :one SET "
                + TcgInventoryItem.SKU_ID
                + " = :skuId, "
                + TcgInventoryItem.SCRYFALL_ID
                + " = :scryfallId, "
                + "#finish = :finish, "
                + "#condition = :condition, "
                + "#name = :cardName, "
                + TcgInventoryItem.SET_CODE
                + " = :setCode, "
                + TcgInventoryItem.SET_NAME
                + " = :setName, "
                + TcgInventoryItem.COLLECTOR_NUMBER
                + " = :collectorNumber, "
                + TcgInventoryItem.DIRTY
                + " = :dirty, "
                + TcgInventoryItem.GSI1PK
                + " = :gsi1pk, "
                + TcgInventoryItem.GSI1SK
                + " = :gsi1sk, "
                + TcgInventoryItem.GSI2PK
                + " = :gsi2pk, "
                + TcgInventoryItem.GSI2SK
                + " = :gsi2sk");

    var values = new HashMap<String, AttributeValue>();
    values.put(":one", AttributeValue.builder().n("1").build());
    values.put(":skuId", AttributeValue.builder().s(skuSeed.getSkuId()).build());
    values.put(":scryfallId", AttributeValue.builder().s(skuSeed.getScryfallId()).build());
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
      expression.append(", " + TcgInventoryItem.SUGGESTED_PRICE + " = :suggestedPrice");
      values.put(
          ":suggestedPrice", AttributeValue.builder().s(skuSeed.getSuggestedPrice()).build());
    }
    if (skuSeed.getFetchtcgCardId() != null) {
      expression.append(", " + TcgInventoryItem.FETCHTCG_CARD_ID + " = :fetchtcgCardId");
      values.put(
          ":fetchtcgCardId", AttributeValue.builder().s(skuSeed.getFetchtcgCardId()).build());
    }
    if (skuSeed.getFetchtcgSetId() != null) {
      expression.append(", " + TcgInventoryItem.FETCHTCG_SET_ID + " = :fetchtcgSetId");
      values.put(
          ":fetchtcgSetId",
          AttributeValue.builder().n(String.valueOf(skuSeed.getFetchtcgSetId())).build());
    }

    return TransactWriteItem.builder()
        .update(
            Update.builder()
                .tableName(TcgInventoryItem.TABLE_NAME)
                .key(
                    Map.of(
                        TcgInventoryItem.PK, AttributeValue.builder().s(skuSeed.getPk()).build(),
                        TcgInventoryItem.SK, AttributeValue.builder().s(skuSeed.getSk()).build()))
                .updateExpression(expression.toString())
                .expressionAttributeNames(
                    Map.of(
                        "#finish", TcgInventoryItem.FINISH,
                        "#condition", TcgInventoryItem.CONDITION,
                        "#name", TcgInventoryItem.NAME))
                .expressionAttributeValues(values)
                .build())
        .build();
  }

  private TransactWriteItem buildSkuDirtyUpdate(String user, String skuId) {
    var skuPk = TcgInventoryItem.formatSkuPk(user, skuId);

    return TransactWriteItem.builder()
        .update(
            Update.builder()
                .tableName(TcgInventoryItem.TABLE_NAME)
                .key(
                    Map.of(
                        TcgInventoryItem.PK, AttributeValue.builder().s(skuPk).build(),
                        TcgInventoryItem.SK,
                            AttributeValue.builder().s(TcgInventoryItem.formatSkuSk()).build()))
                .updateExpression(
                    "ADD "
                        + TcgInventoryItem.VERSION
                        + " :one SET "
                        + TcgInventoryItem.DIRTY
                        + " = :dirty, "
                        + TcgInventoryItem.GSI1PK
                        + " = :gsi1pk")
                .expressionAttributeValues(
                    Map.of(
                        ":one", AttributeValue.builder().n("1").build(),
                        ":dirty", AttributeValue.builder().bool(true).build(),
                        ":gsi1pk",
                            AttributeValue.builder()
                                .s(TcgInventoryItem.formatGsi1pk(user))
                                .build()))
                .build())
        .build();
  }

  private TransactWriteItem buildSkuVersionBump(String user, String skuId) {
    var skuPk = TcgInventoryItem.formatSkuPk(user, skuId);

    return TransactWriteItem.builder()
        .update(
            Update.builder()
                .tableName(TcgInventoryItem.TABLE_NAME)
                .key(
                    Map.of(
                        TcgInventoryItem.PK, AttributeValue.builder().s(skuPk).build(),
                        TcgInventoryItem.SK,
                            AttributeValue.builder().s(TcgInventoryItem.formatSkuSk()).build()))
                .updateExpression("ADD " + TcgInventoryItem.VERSION + " :one")
                .expressionAttributeValues(Map.of(":one", AttributeValue.builder().n("1").build()))
                .build())
        .build();
  }

  private TransactWriteItem buildOrderFulfilledUpdate(String user, String orderId) {
    var userPk = TcgInventoryItem.formatUserPk(user);
    var orderSk = TcgInventoryItem.formatOrderSk(orderId);

    return TransactWriteItem.builder()
        .update(
            Update.builder()
                .tableName(TcgInventoryItem.TABLE_NAME)
                .key(
                    Map.of(
                        TcgInventoryItem.PK, AttributeValue.builder().s(userPk).build(),
                        TcgInventoryItem.SK, AttributeValue.builder().s(orderSk).build()))
                .updateExpression(
                    "SET #status = :fulfilled, " + TcgInventoryItem.UPDATED_AT + " = :now")
                .conditionExpression("#status = :toPick")
                .expressionAttributeNames(Map.of("#status", TcgInventoryItem.STATUS))
                .expressionAttributeValues(
                    Map.of(
                        ":fulfilled", AttributeValue.builder().s("fulfilled").build(),
                        ":toPick", AttributeValue.builder().s("to_pick").build(),
                        ":now",
                            AttributeValue.builder()
                                .n(String.valueOf(clock.now().getEpochSecond()))
                                .build()))
                .build())
        .build();
  }

  private TransactWriteItem buildAuditPut(
      String user, String eventType, Map<String, AttributeValue> attributes) {
    var auditItem = new HashMap<>(attributes);
    auditItem.put(
        TcgInventoryItem.PK,
        AttributeValue.builder().s(TcgInventoryItem.formatAuditPk(user)).build());
    auditItem.put(
        TcgInventoryItem.SK, AttributeValue.builder().s(ulidGenerator.generate()).build());
    auditItem.put(TcgInventoryItem.EVENT_TYPE, AttributeValue.builder().s(eventType).build());
    auditItem.put(
        TcgInventoryItem.CREATED_AT,
        AttributeValue.builder().n(String.valueOf(clock.now().getEpochSecond())).build());

    return TransactWriteItem.builder()
        .put(Put.builder().tableName(TcgInventoryItem.TABLE_NAME).item(auditItem).build())
        .build();
  }
}
