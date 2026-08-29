package com.jordansimsmith.tcginventory;

import com.jordansimsmith.time.Clock;
import com.jordansimsmith.ulid.UlidGenerator;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryEnhancedRequest;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.Put;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;
import software.amazon.awssdk.services.dynamodb.model.Update;

public class TcgInventoryItemRepository {
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

  public List<TcgInventoryItem> findUnitsToAllocate(
      String user, String skuId, String orderId, int quantity) {
    var results = new ArrayList<TcgInventoryItem>();
    var request =
        QueryEnhancedRequest.builder()
            .queryConditional(
                QueryConditional.sortBeginsWith(
                    Key.builder()
                        .partitionValue(TcgInventoryItem.formatSkuPk(user, skuId))
                        .sortValue(TcgInventoryItem.UNIT_PREFIX)
                        .build()))
            .build();

    for (var item : tcgInventoryTable.query(request).items()) {
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
    transactItems.add(buildAuditPut(user, "reserve", orderItem.getOrderId()));

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
    transactItems.add(buildAuditPut(user, "sell", orderId));

    executeChunked(transactItems);
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

  private TransactWriteItem buildAuditPut(String user, String eventType, String orderId) {
    var auditItem = new HashMap<String, AttributeValue>();
    auditItem.put(
        TcgInventoryItem.PK,
        AttributeValue.builder().s(TcgInventoryItem.formatAuditPk(user)).build());
    auditItem.put(
        TcgInventoryItem.SK, AttributeValue.builder().s(ulidGenerator.generate()).build());
    auditItem.put(TcgInventoryItem.EVENT_TYPE, AttributeValue.builder().s(eventType).build());
    auditItem.put(TcgInventoryItem.ORDER_ID, AttributeValue.builder().s(orderId).build());
    auditItem.put(
        TcgInventoryItem.CREATED_AT,
        AttributeValue.builder().n(String.valueOf(clock.now().getEpochSecond())).build());

    return TransactWriteItem.builder()
        .put(Put.builder().tableName(TcgInventoryItem.TABLE_NAME).item(auditItem).build())
        .build();
  }
}
