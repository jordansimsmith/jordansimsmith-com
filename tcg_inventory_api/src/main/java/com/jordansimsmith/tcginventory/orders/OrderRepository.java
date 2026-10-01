package com.jordansimsmith.tcginventory.orders;

import com.jordansimsmith.tcginventory.AuditItem;
import com.jordansimsmith.tcginventory.JobItem;
import com.jordansimsmith.tcginventory.TcgInventoryTable;
import com.jordansimsmith.tcginventory.inventory.InventoryRepository;
import com.jordansimsmith.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.Put;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;
import software.amazon.awssdk.services.dynamodb.model.Update;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;

public class OrderRepository {
  private record AllocatedUnit(String skuId, int sequenceNumber) {}

  private final DynamoDbTable<OrderItem> orderTable;
  private final DynamoDbTable<JobItem> jobTable;
  private final InventoryRepository inventoryRepository;
  private final DynamoDbClient dynamoDbClient;
  private final Clock clock;

  public OrderRepository(
      DynamoDbTable<OrderItem> orderTable,
      DynamoDbTable<JobItem> jobTable,
      InventoryRepository inventoryRepository,
      DynamoDbClient dynamoDbClient,
      Clock clock) {
    this.orderTable = orderTable;
    this.jobTable = jobTable;
    this.inventoryRepository = inventoryRepository;
    this.dynamoDbClient = dynamoDbClient;
    this.clock = clock;
  }

  public void createReservingOrder(OrderItem order) {
    dynamoDbClient.putItem(
        PutItemRequest.builder()
            .tableName(TcgInventoryTable.TABLE_NAME)
            .item(orderTable.tableSchema().itemToMap(order, true))
            .conditionExpression("attribute_not_exists(pk)")
            .build());
  }

  public void finishReservation(String user, String orderId, String targetState) {
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
                    .updateExpression("SET #status = :target, " + OrderItem.UPDATED_AT + " = :now")
                    .conditionExpression("#status = :reserving")
                    .expressionAttributeNames(Map.of("#status", OrderItem.STATUS))
                    .expressionAttributeValues(
                        Map.of(
                            ":target", AttributeValue.builder().s(targetState).build(),
                            ":reserving", AttributeValue.builder().s("reserving").build(),
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
                    "order_reserved",
                    Map.of(
                        OrderItem.ORDER_ID, AttributeValue.builder().s(orderId).build(),
                        AuditItem.BEFORE_STATUS, AttributeValue.builder().s("reserving").build(),
                        AuditItem.AFTER_STATUS, AttributeValue.builder().s(targetState).build())))
            .build());
  }

  public void startFulfilling(String user, OrderItem order, JobItem job) {
    if (order.getLines().isEmpty()) {
      throw new IllegalStateException("order has no lines to fulfill");
    }
    for (var line : order.getLines()) {
      if (line.getQuantity() <= 0
          || line.getAllocatedSequenceNumbers().size() != line.getQuantity()) {
        throw new IllegalStateException("order line is not fully allocated");
      }
    }
    var allocated = allocatedUnits(order);
    if (allocated.size() != new HashSet<>(allocated).size()) {
      throw new IllegalStateException("order allocation contains a duplicate unit");
    }
    for (var key : allocated) {
      var unit = inventoryRepository.getUnit(user, key.skuId(), key.sequenceNumber());
      if (unit == null
          || !"reserved".equals(unit.getStatus())
          || !order.getOrderId().equals(unit.getOrderId())) {
        throw new IllegalStateException("order has an unexpected allocated unit");
      }
    }

    var startOrder =
        TransactWriteItem.builder()
            .update(
                Update.builder()
                    .tableName(TcgInventoryTable.TABLE_NAME)
                    .key(orderKey(user, order.getOrderId()))
                    .updateExpression(
                        "SET #status = :fulfilling, " + OrderItem.UPDATED_AT + " = :now")
                    .conditionExpression("#status = :toPick")
                    .expressionAttributeNames(Map.of("#status", OrderItem.STATUS))
                    .expressionAttributeValues(
                        Map.of(
                            ":fulfilling", AttributeValue.builder().s("fulfilling").build(),
                            ":toPick", AttributeValue.builder().s("to_pick").build(),
                            ":now",
                                AttributeValue.builder()
                                    .n(String.valueOf(clock.now().getEpochSecond()))
                                    .build()))
                    .build())
            .build();
    var createJob =
        TransactWriteItem.builder()
            .put(
                Put.builder()
                    .tableName(TcgInventoryTable.TABLE_NAME)
                    .item(jobTable.tableSchema().itemToMap(job, true))
                    .conditionExpression("attribute_not_exists(" + JobItem.PK + ")")
                    .build())
            .build();

    dynamoDbClient.transactWriteItems(
        TransactWriteItemsRequest.builder().transactItems(startOrder, createJob).build());
  }

  public void finishFulfilling(String user, String orderId) {
    var finishOrder =
        TransactWriteItem.builder()
            .update(
                Update.builder()
                    .tableName(TcgInventoryTable.TABLE_NAME)
                    .key(orderKey(user, orderId))
                    .updateExpression(
                        "SET #status = :fulfilled, " + OrderItem.UPDATED_AT + " = :now")
                    .conditionExpression("#status = :fulfilling")
                    .expressionAttributeNames(Map.of("#status", OrderItem.STATUS))
                    .expressionAttributeValues(
                        Map.of(
                            ":fulfilled", AttributeValue.builder().s("fulfilled").build(),
                            ":fulfilling", AttributeValue.builder().s("fulfilling").build(),
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
                    "order_fulfilled",
                    Map.of(
                        OrderItem.ORDER_ID, AttributeValue.builder().s(orderId).build(),
                        AuditItem.BEFORE_STATUS, AttributeValue.builder().s("fulfilling").build(),
                        AuditItem.AFTER_STATUS, AttributeValue.builder().s("fulfilled").build())))
            .build());
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

  private Map<String, AttributeValue> orderKey(String user, String orderId) {
    return Map.of(
        OrderItem.PK, AttributeValue.builder().s(OrderItem.formatPk(user)).build(),
        OrderItem.SK, AttributeValue.builder().s(OrderItem.formatSk(orderId)).build());
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
