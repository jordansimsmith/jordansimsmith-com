package com.jordansimsmith.tcginventory.orders;

import com.jordansimsmith.tcginventory.AuditItem;
import com.jordansimsmith.tcginventory.TcgInventoryTable;
import com.jordansimsmith.tcginventory.inventory.InventoryRepository;
import com.jordansimsmith.tcginventory.inventory.UnitItem;
import com.jordansimsmith.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.Put;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;
import software.amazon.awssdk.services.dynamodb.model.Update;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;

public class OrderRepository {
  public record SkuUnits(String skuId, List<Integer> sequenceNumbers) {}

  private record AllocatedUnit(String skuId, int sequenceNumber) {}

  private final DynamoDbTable<OrderItem> orderTable;
  private final InventoryRepository inventoryRepository;
  private final DynamoDbClient dynamoDbClient;
  private final Clock clock;

  public OrderRepository(
      DynamoDbTable<OrderItem> orderTable,
      InventoryRepository inventoryRepository,
      DynamoDbClient dynamoDbClient,
      Clock clock) {
    this.orderTable = orderTable;
    this.inventoryRepository = inventoryRepository;
    this.dynamoDbClient = dynamoDbClient;
    this.clock = clock;
  }

  public List<UnitItem> findUnitsToAllocate(
      String user, String skuId, String orderId, int quantity) {
    return inventoryRepository.findUnitsToAllocate(user, skuId, orderId, quantity);
  }

  public void reserveOrder(String user, OrderItem orderItem, List<SkuUnits> reservations) {
    var transactItems = new ArrayList<TransactWriteItem>();
    for (var reservation : reservations) {
      transactItems.add(inventoryRepository.buildSkuDirtyUpdate(user, reservation.skuId()));
      for (var sequenceNumber : reservation.sequenceNumbers()) {
        transactItems.add(
            inventoryRepository.buildUnitReserveUpdate(
                user, reservation.skuId(), sequenceNumber, orderItem.getOrderId()));
      }
    }
    transactItems.add(
        TransactWriteItem.builder()
            .put(
                Put.builder()
                    .tableName(TcgInventoryTable.TABLE_NAME)
                    .item(orderTable.tableSchema().itemToMap(orderItem, true))
                    .conditionExpression("attribute_not_exists(pk)")
                    .build())
            .build());
    transactItems.add(
        inventoryRepository.buildAuditPut(
            user,
            "reserve",
            Map.of(
                OrderItem.ORDER_ID, AttributeValue.builder().s(orderItem.getOrderId()).build())));
    inventoryRepository.executeChunked(transactItems);
  }

  public void advanceOrderToPickReady(
      String user, String orderId, String fetchtcgStatus, String fetchtcgCurrentAction) {
    dynamoDbClient.transactWriteItems(
        TransactWriteItemsRequest.builder()
            .transactItems(
                List.of(
                    buildOrderPickReadyUpdate(user, orderId, fetchtcgStatus, fetchtcgCurrentAction),
                    inventoryRepository.buildAuditPut(
                        user,
                        "payment",
                        Map.of(
                            OrderItem.ORDER_ID,
                            AttributeValue.builder().s(orderId).build(),
                            AuditItem.BEFORE_STATUS,
                            AttributeValue.builder().s("awaiting_payment").build(),
                            AuditItem.AFTER_STATUS,
                            AttributeValue.builder().s("to_pick").build()))))
            .build());
  }

  public void updateOrderFulfillment(
      String user,
      String orderId,
      @Nullable String buyerName,
      @Nullable OrderItem.BuyerAddress buyerAddress,
      @Nullable String postageOption) {
    dynamoDbClient.updateItem(
        UpdateItemRequest.builder()
            .tableName(TcgInventoryTable.TABLE_NAME)
            .key(
                Map.of(
                    OrderItem.PK,
                    AttributeValue.builder().s(OrderItem.formatPk(user)).build(),
                    OrderItem.SK,
                    AttributeValue.builder().s(OrderItem.formatSk(orderId)).build()))
            .updateExpression(
                "SET "
                    + OrderItem.BUYER_NAME
                    + " = :buyerName, "
                    + OrderItem.BUYER_ADDRESS
                    + " = :buyerAddress, "
                    + OrderItem.POSTAGE_OPTION
                    + " = :postageOption, "
                    + OrderItem.UPDATED_AT
                    + " = :now")
            .conditionExpression("attribute_exists(" + OrderItem.PK + ")")
            .expressionAttributeValues(
                Map.of(
                    ":buyerName", toAttributeValue(buyerName),
                    ":buyerAddress", toAttributeValue(buyerAddress),
                    ":postageOption", toAttributeValue(postageOption),
                    ":now",
                        AttributeValue.builder()
                            .n(String.valueOf(clock.now().getEpochSecond()))
                            .build()))
            .build());
  }

  public void startVoiding(String user, OrderItem order, String cancelledStatus) {
    var allocated = allocatedUnits(order);
    for (var unitKey : allocated) {
      var unit = inventoryRepository.getUnit(user, unitKey.skuId(), unitKey.sequenceNumber());
      if (unit == null
          || !"reserved".equals(unit.getStatus())
          || !order.getOrderId().equals(unit.getOrderId())) {
        throw new IllegalStateException("order has an unexpected allocated unit");
      }
    }

    dynamoDbClient.updateItem(
        UpdateItemRequest.builder()
            .tableName(TcgInventoryTable.TABLE_NAME)
            .key(
                Map.of(
                    OrderItem.PK,
                    AttributeValue.builder().s(OrderItem.formatPk(user)).build(),
                    OrderItem.SK,
                    AttributeValue.builder().s(OrderItem.formatSk(order.getOrderId())).build()))
            .updateExpression(
                "SET #status = :voiding, "
                    + OrderItem.FETCHTCG_STATUS
                    + " = :external, "
                    + OrderItem.UPDATED_AT
                    + " = :now")
            .conditionExpression("#status = :awaiting")
            .expressionAttributeNames(Map.of("#status", OrderItem.STATUS))
            .expressionAttributeValues(
                Map.of(
                    ":voiding", AttributeValue.builder().s("voiding").build(),
                    ":awaiting", AttributeValue.builder().s("awaiting_payment").build(),
                    ":external", AttributeValue.builder().s(cancelledStatus).build(),
                    ":now",
                        AttributeValue.builder()
                            .n(String.valueOf(clock.now().getEpochSecond()))
                            .build()))
            .build());
  }

  public void finishVoiding(String user, String orderId) {
    var finishOrder =
        TransactWriteItem.builder()
            .update(
                Update.builder()
                    .tableName(TcgInventoryTable.TABLE_NAME)
                    .key(
                        Map.of(
                            OrderItem.PK,
                            AttributeValue.builder().s(OrderItem.formatPk(user)).build(),
                            OrderItem.SK,
                            AttributeValue.builder().s(OrderItem.formatSk(orderId)).build()))
                    .updateExpression(
                        "SET #status = :voided, "
                            + OrderItem.UPDATED_AT
                            + " = :now REMOVE "
                            + OrderItem.FETCHTCG_CURRENT_ACTION)
                    .conditionExpression("#status = :voiding")
                    .expressionAttributeNames(Map.of("#status", OrderItem.STATUS))
                    .expressionAttributeValues(
                        Map.of(
                            ":voided", AttributeValue.builder().s("voided").build(),
                            ":voiding", AttributeValue.builder().s("voiding").build(),
                            ":now",
                                AttributeValue.builder()
                                    .n(String.valueOf(clock.now().getEpochSecond()))
                                    .build()))
                    .build())
            .build();

    dynamoDbClient.transactWriteItems(
        TransactWriteItemsRequest.builder()
            .transactItems(
                finishOrder,
                inventoryRepository.buildAuditPut(
                    user,
                    "order_voided",
                    Map.of(OrderItem.ORDER_ID, AttributeValue.builder().s(orderId).build())))
            .build());
  }

  private List<AllocatedUnit> allocatedUnits(OrderItem order) {
    var result = new ArrayList<AllocatedUnit>();
    for (var line : order.getLines()) {
      for (var sequenceNumber : line.getAllocatedSequenceNumbers()) {
        result.add(new AllocatedUnit(line.getSkuId(), sequenceNumber));
      }
    }
    return result;
  }

  public void sellOrder(String user, String orderId, List<SkuUnits> sales) {
    var transactItems = new ArrayList<TransactWriteItem>();
    for (var sale : sales) {
      transactItems.add(inventoryRepository.buildSkuVersionBump(user, sale.skuId()));
      for (var sequenceNumber : sale.sequenceNumbers()) {
        transactItems.add(
            inventoryRepository.buildUnitSellUpdate(user, sale.skuId(), sequenceNumber));
      }
    }
    transactItems.add(buildOrderFulfilledUpdate(user, orderId));
    transactItems.add(
        inventoryRepository.buildAuditPut(
            user, "sell", Map.of(OrderItem.ORDER_ID, AttributeValue.builder().s(orderId).build())));
    inventoryRepository.executeChunked(transactItems);
  }

  private TransactWriteItem buildOrderPickReadyUpdate(
      String user, String orderId, String fetchtcgStatus, String fetchtcgCurrentAction) {
    return TransactWriteItem.builder()
        .update(
            Update.builder()
                .tableName(TcgInventoryTable.TABLE_NAME)
                .key(
                    Map.of(
                        OrderItem.PK,
                        AttributeValue.builder().s(OrderItem.formatPk(user)).build(),
                        OrderItem.SK,
                        AttributeValue.builder().s(OrderItem.formatSk(orderId)).build()))
                .updateExpression(
                    "SET #status = :toPick, "
                        + OrderItem.FETCHTCG_STATUS
                        + " = :fetchtcgStatus, "
                        + OrderItem.FETCHTCG_CURRENT_ACTION
                        + " = :fetchtcgCurrentAction, "
                        + OrderItem.UPDATED_AT
                        + " = :now")
                .conditionExpression("#status = :awaitingPayment")
                .expressionAttributeNames(Map.of("#status", OrderItem.STATUS))
                .expressionAttributeValues(
                    Map.of(
                        ":toPick", AttributeValue.builder().s("to_pick").build(),
                        ":awaitingPayment", AttributeValue.builder().s("awaiting_payment").build(),
                        ":fetchtcgStatus", AttributeValue.builder().s(fetchtcgStatus).build(),
                        ":fetchtcgCurrentAction",
                            AttributeValue.builder().s(fetchtcgCurrentAction).build(),
                        ":now",
                            AttributeValue.builder()
                                .n(String.valueOf(clock.now().getEpochSecond()))
                                .build()))
                .build())
        .build();
  }

  private TransactWriteItem buildOrderFulfilledUpdate(String user, String orderId) {
    return TransactWriteItem.builder()
        .update(
            Update.builder()
                .tableName(TcgInventoryTable.TABLE_NAME)
                .key(
                    Map.of(
                        OrderItem.PK,
                        AttributeValue.builder().s(OrderItem.formatPk(user)).build(),
                        OrderItem.SK,
                        AttributeValue.builder().s(OrderItem.formatSk(orderId)).build()))
                .updateExpression("SET #status = :fulfilled, " + OrderItem.UPDATED_AT + " = :now")
                .conditionExpression("#status = :toPick")
                .expressionAttributeNames(Map.of("#status", OrderItem.STATUS))
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

  private static AttributeValue toAttributeValue(@Nullable String value) {
    return value == null
        ? AttributeValue.builder().nul(true).build()
        : AttributeValue.builder().s(value).build();
  }

  private static AttributeValue toAttributeValue(@Nullable OrderItem.BuyerAddress address) {
    if (address == null) {
      return AttributeValue.builder().nul(true).build();
    }
    var parts = new HashMap<String, AttributeValue>();
    putAddressPart(parts, OrderItem.BuyerAddress.LINE1, address.getLine1());
    putAddressPart(parts, OrderItem.BuyerAddress.LINE2, address.getLine2());
    putAddressPart(parts, OrderItem.BuyerAddress.SUBURB, address.getSuburb());
    putAddressPart(parts, OrderItem.BuyerAddress.CITY, address.getCity());
    putAddressPart(parts, OrderItem.BuyerAddress.POST_CODE, address.getPostCode());
    putAddressPart(parts, OrderItem.BuyerAddress.COUNTRY, address.getCountry());
    return AttributeValue.builder().m(parts).build();
  }

  private static void putAddressPart(
      Map<String, AttributeValue> parts, String attribute, @Nullable String value) {
    if (value != null) {
      parts.put(attribute, AttributeValue.builder().s(value).build());
    }
  }
}
