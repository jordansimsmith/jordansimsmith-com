package com.jordansimsmith.tcginventory.orders;

import com.jordansimsmith.tcginventory.JobItem;
import com.jordansimsmith.tcginventory.TcgInventoryTable;
import com.jordansimsmith.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryEnhancedRequest;
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
  private final DynamoDbTable<OrderRowItem> rowTable;
  private final DynamoDbClient dynamoDbClient;
  private final Clock clock;

  public OrderRepository(
      DynamoDbTable<OrderItem> orderTable,
      DynamoDbTable<OrderRowItem> rowTable,
      DynamoDbTable<JobItem> jobTable,
      DynamoDbClient dynamoDbClient,
      Clock clock) {
    this.orderTable = orderTable;
    this.rowTable = rowTable;
    this.jobTable = jobTable;
    this.dynamoDbClient = dynamoDbClient;
    this.clock = clock;
  }

  public void createReservingOrder(OrderItem order, List<OrderRowItem> rows) {
    for (var row : rows) {
      rowTable.putItem(row);
    }

    dynamoDbClient.putItem(
        PutItemRequest.builder()
            .tableName(TcgInventoryTable.TABLE_NAME)
            .item(orderTable.tableSchema().itemToMap(order, true))
            .conditionExpression("attribute_not_exists(pk)")
            .build());
  }

  public void recordReservation(
      String user, String orderId, int position, int unitIndex, int sequenceNumber) {
    var now = AttributeValue.builder().n(String.valueOf(clock.now().getEpochSecond())).build();
    dynamoDbClient.transactWriteItems(
        TransactWriteItemsRequest.builder()
            .transactItems(
                TransactWriteItem.builder()
                    .update(
                        Update.builder()
                            .tableName(TcgInventoryTable.TABLE_NAME)
                            .key(orderKey(user, orderId))
                            .updateExpression("SET updated_at = :now")
                            .conditionExpression("#status = :reserving")
                            .expressionAttributeNames(Map.of("#status", "status"))
                            .expressionAttributeValues(
                                Map.of(
                                    ":now",
                                    now,
                                    ":reserving",
                                    AttributeValue.builder().s("reserving").build()))
                            .build())
                    .build(),
                TransactWriteItem.builder()
                    .update(
                        Update.builder()
                            .tableName(TcgInventoryTable.TABLE_NAME)
                            .key(
                                Map.of(
                                    "pk",
                                    AttributeValue.builder()
                                        .s(OrderRowItem.formatPk(user, orderId))
                                        .build(),
                                    "sk",
                                    AttributeValue.builder()
                                        .s(OrderRowItem.formatSk(position))
                                        .build()))
                            .updateExpression(
                                "SET allocated_sequence_numbers[" + unitIndex + "] = :sequence")
                            .conditionExpression(
                                "attribute_exists(pk) AND size(allocated_sequence_numbers) ="
                                    + " :unitIndex")
                            .expressionAttributeValues(
                                Map.of(
                                    ":sequence",
                                    AttributeValue.builder()
                                        .n(String.valueOf(sequenceNumber))
                                        .build(),
                                    ":unitIndex",
                                    AttributeValue.builder().n(String.valueOf(unitIndex)).build()))
                            .build())
                    .build())
            .build());
  }

  public List<OrderRowItem> findRows(String user, OrderItem order) {
    if (order.getRowCount() == 0) {
      return List.of();
    }
    var rows =
        rowTable
            .query(
                QueryEnhancedRequest.builder()
                    .queryConditional(
                        QueryConditional.sortBetween(
                            Key.builder()
                                .partitionValue(OrderRowItem.formatPk(user, order.getOrderId()))
                                .sortValue(OrderRowItem.formatSk(0))
                                .build(),
                            Key.builder()
                                .partitionValue(OrderRowItem.formatPk(user, order.getOrderId()))
                                .sortValue(OrderRowItem.formatSk(order.getRowCount() - 1))
                                .build()))
                    .consistentRead(true)
                    .scanIndexForward(true)
                    .build())
            .items()
            .stream()
            .toList();
    if (rows.size() != order.getRowCount()) {
      throw new IllegalStateException("order rows are incomplete");
    }
    return rows;
  }

  public void finishReservation(String user, String orderId, String targetState) {
    dynamoDbClient.updateItem(
        UpdateItemRequest.builder()
            .tableName(TcgInventoryTable.TABLE_NAME)
            .key(orderKey(user, orderId))
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
            .build());
  }

  public void startFulfilling(String user, OrderItem order, List<OrderRowItem> rows, JobItem job) {
    if (rows.isEmpty()) {
      throw new IllegalStateException("order has no lines to fulfill");
    }
    for (var line : rows) {
      if (line.getQuantity() <= 0
          || line.getAllocatedSequenceNumbers().size() != line.getQuantity()) {
        throw new IllegalStateException("order line is not fully allocated");
      }
    }
    var allocated = allocatedUnits(rows);
    if (allocated.size() != new HashSet<>(allocated).size()) {
      throw new IllegalStateException("order allocation contains a duplicate unit");
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
    dynamoDbClient.updateItem(
        UpdateItemRequest.builder()
            .tableName(TcgInventoryTable.TABLE_NAME)
            .key(orderKey(user, orderId))
            .updateExpression("SET #status = :fulfilled, " + OrderItem.UPDATED_AT + " = :now")
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
            .build());
  }

  public void advanceOrderToPickReady(
      String user, String orderId, String fetchtcgStatus, String fetchtcgCurrentAction) {
    dynamoDbClient.updateItem(
        UpdateItemRequest.builder()
            .tableName(TcgInventoryTable.TABLE_NAME)
            .key(orderKey(user, orderId))
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
    dynamoDbClient.updateItem(
        UpdateItemRequest.builder()
            .tableName(TcgInventoryTable.TABLE_NAME)
            .key(orderKey(user, orderId))
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
            .build());
  }

  private List<AllocatedUnit> allocatedUnits(List<OrderRowItem> rows) {
    var result = new ArrayList<AllocatedUnit>();
    for (var line : rows) {
      for (var sequenceNumber : line.getAllocatedSequenceNumbers()) {
        result.add(new AllocatedUnit(line.getSkuId(), sequenceNumber));
      }
    }
    return result;
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
