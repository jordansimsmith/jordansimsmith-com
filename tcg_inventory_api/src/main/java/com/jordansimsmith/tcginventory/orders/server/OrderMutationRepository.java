package com.jordansimsmith.tcginventory.orders.server;

import com.jordansimsmith.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;
import software.amazon.awssdk.services.dynamodb.model.Update;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;

public class OrderMutationRepository {
  private final DynamoDbTable<OrderItem> orderTable;
  private final DynamoDbTable<OrderRowItem> rowTable;
  private final DynamoDbClient dynamoDbClient;
  private final String tableName;
  private final Clock clock;

  public OrderMutationRepository(
      DynamoDbTable<OrderItem> orderTable,
      DynamoDbTable<OrderRowItem> rowTable,
      DynamoDbClient dynamoDbClient,
      String tableName,
      Clock clock) {
    this.orderTable = orderTable;
    this.rowTable = rowTable;
    this.dynamoDbClient = dynamoDbClient;
    this.tableName = tableName;
    this.clock = clock;
  }

  public void createReservingOrder(OrderItem order, List<OrderRowItem> rows) {
    for (var row : rows) {
      rowTable.putItem(row);
    }
    dynamoDbClient.putItem(
        PutItemRequest.builder()
            .tableName(tableName)
            .item(orderTable.tableSchema().itemToMap(order, true))
            .conditionExpression("attribute_not_exists(pk)")
            .build());
  }

  public void recordReservation(
      String user, String orderId, int position, int unitIndex, int sequenceNumber) {
    var now = AttributeValue.fromN(Long.toString(clock.now().getEpochSecond()));
    dynamoDbClient.transactWriteItems(
        TransactWriteItemsRequest.builder()
            .transactItems(
                TransactWriteItem.builder()
                    .update(
                        Update.builder()
                            .tableName(tableName)
                            .key(orderKey(user, orderId))
                            .updateExpression("SET updated_at = :now")
                            .conditionExpression("#status = :reserving")
                            .expressionAttributeNames(Map.of("#status", "status"))
                            .expressionAttributeValues(
                                Map.of(
                                    ":now", now, ":reserving", AttributeValue.fromS("reserving")))
                            .build())
                    .build(),
                TransactWriteItem.builder()
                    .update(
                        Update.builder()
                            .tableName(tableName)
                            .key(rowKey(user, orderId, position))
                            .updateExpression(
                                "SET allocated_sequence_numbers[" + unitIndex + "] = :sequence")
                            .conditionExpression(
                                "attribute_exists(pk) AND size(allocated_sequence_numbers) ="
                                    + " :unitIndex")
                            .expressionAttributeValues(
                                Map.of(
                                    ":sequence",
                                        AttributeValue.fromN(Integer.toString(sequenceNumber)),
                                    ":unitIndex",
                                        AttributeValue.fromN(Integer.toString(unitIndex))))
                            .build())
                    .build())
            .build());
  }

  public void finishReservation(String user, String orderId, String targetState) {
    dynamoDbClient.updateItem(
        UpdateItemRequest.builder()
            .tableName(tableName)
            .key(orderKey(user, orderId))
            .updateExpression("SET #status = :target, updated_at = :now")
            .conditionExpression("#status = :reserving")
            .expressionAttributeNames(Map.of("#status", "status"))
            .expressionAttributeValues(
                Map.of(
                    ":target", AttributeValue.fromS(targetState),
                    ":reserving", AttributeValue.fromS("reserving"),
                    ":now", now()))
            .build());
  }

  public void advanceOrderToPickReady(
      String user, String orderId, String fetchtcgStatus, String fetchtcgCurrentAction) {
    dynamoDbClient.updateItem(
        UpdateItemRequest.builder()
            .tableName(tableName)
            .key(orderKey(user, orderId))
            .updateExpression(
                "SET #status = :toPick, fetchtcg_status = :fetchtcgStatus, "
                    + "fetchtcg_current_action = :fetchtcgCurrentAction, updated_at = :now")
            .conditionExpression("#status = :awaitingPayment")
            .expressionAttributeNames(Map.of("#status", "status"))
            .expressionAttributeValues(
                Map.of(
                    ":toPick", AttributeValue.fromS("to_pick"),
                    ":awaitingPayment", AttributeValue.fromS("awaiting_payment"),
                    ":fetchtcgStatus", AttributeValue.fromS(fetchtcgStatus),
                    ":fetchtcgCurrentAction", AttributeValue.fromS(fetchtcgCurrentAction),
                    ":now", now()))
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
            .tableName(tableName)
            .key(orderKey(user, orderId))
            .updateExpression(
                "SET buyer_name = :buyerName, buyer_address = :buyerAddress, "
                    + "postage_option = :postageOption, updated_at = :now")
            .conditionExpression("attribute_exists(pk)")
            .expressionAttributeValues(
                Map.of(
                    ":buyerName", stringValue(buyerName),
                    ":buyerAddress", addressValue(buyerAddress),
                    ":postageOption", stringValue(postageOption),
                    ":now", now()))
            .build());
  }

  public void startVoiding(String user, OrderItem order, String cancelledStatus) {
    dynamoDbClient.updateItem(
        UpdateItemRequest.builder()
            .tableName(tableName)
            .key(orderKey(user, order.getOrderId()))
            .updateExpression(
                "SET #status = :voiding, fetchtcg_status = :external, updated_at = :now")
            .conditionExpression("#status = :awaiting")
            .expressionAttributeNames(Map.of("#status", "status"))
            .expressionAttributeValues(
                Map.of(
                    ":voiding", AttributeValue.fromS("voiding"),
                    ":awaiting", AttributeValue.fromS("awaiting_payment"),
                    ":external", AttributeValue.fromS(cancelledStatus),
                    ":now", now()))
            .build());
  }

  public void finishVoiding(String user, String orderId) {
    dynamoDbClient.updateItem(
        UpdateItemRequest.builder()
            .tableName(tableName)
            .key(orderKey(user, orderId))
            .updateExpression(
                "SET #status = :voided, updated_at = :now REMOVE fetchtcg_current_action")
            .conditionExpression("#status = :voiding")
            .expressionAttributeNames(Map.of("#status", "status"))
            .expressionAttributeValues(
                Map.of(
                    ":voided", AttributeValue.fromS("voided"),
                    ":voiding", AttributeValue.fromS("voiding"),
                    ":now", now()))
            .build());
  }

  public void startFulfilling(String user, String orderId) {
    var now = now();
    dynamoDbClient.updateItem(
        UpdateItemRequest.builder()
            .tableName(tableName)
            .key(orderKey(user, orderId))
            .updateExpression("SET #status = :fulfilling, updated_at = :now")
            .conditionExpression("#status = :toPick")
            .expressionAttributeNames(Map.of("#status", "status"))
            .expressionAttributeValues(
                Map.of(
                    ":fulfilling", AttributeValue.fromS("fulfilling"),
                    ":toPick", AttributeValue.fromS("to_pick"),
                    ":now", now))
            .build());
  }

  public void finishFulfilling(String user, String orderId) {
    var now = now();
    dynamoDbClient.updateItem(
        UpdateItemRequest.builder()
            .tableName(tableName)
            .key(orderKey(user, orderId))
            .updateExpression("SET #status = :fulfilled, updated_at = :now")
            .conditionExpression("#status = :fulfilling")
            .expressionAttributeNames(Map.of("#status", "status"))
            .expressionAttributeValues(
                Map.of(
                    ":fulfilled", AttributeValue.fromS("fulfilled"),
                    ":fulfilling", AttributeValue.fromS("fulfilling"),
                    ":now", now))
            .build());
  }

  private Map<String, AttributeValue> orderKey(String user, String orderId) {
    return Map.of(
        OrderItem.PK, AttributeValue.fromS(OrderItem.formatPk(user)),
        OrderItem.SK, AttributeValue.fromS(OrderItem.formatSk(orderId)));
  }

  private Map<String, AttributeValue> rowKey(String user, String orderId, int position) {
    return Map.of(
        OrderRowItem.PK, AttributeValue.fromS(OrderRowItem.formatPk(user, orderId)),
        OrderRowItem.SK, AttributeValue.fromS(OrderRowItem.formatSk(position)));
  }

  private AttributeValue now() {
    return AttributeValue.fromN(Long.toString(clock.now().getEpochSecond()));
  }

  private static AttributeValue stringValue(@Nullable String value) {
    return value == null ? AttributeValue.fromNul(true) : AttributeValue.fromS(value);
  }

  private static AttributeValue addressValue(@Nullable OrderItem.BuyerAddress address) {
    if (address == null) {
      return AttributeValue.fromNul(true);
    }
    var parts = new HashMap<String, AttributeValue>();
    addAddressPart(parts, OrderItem.BuyerAddress.LINE1, address.getLine1());
    addAddressPart(parts, OrderItem.BuyerAddress.LINE2, address.getLine2());
    addAddressPart(parts, OrderItem.BuyerAddress.SUBURB, address.getSuburb());
    addAddressPart(parts, OrderItem.BuyerAddress.CITY, address.getCity());
    addAddressPart(parts, OrderItem.BuyerAddress.POST_CODE, address.getPostCode());
    addAddressPart(parts, OrderItem.BuyerAddress.COUNTRY, address.getCountry());
    return AttributeValue.fromM(parts);
  }

  private static void addAddressPart(
      Map<String, AttributeValue> parts, String attribute, @Nullable String value) {
    if (value != null) {
      parts.put(attribute, AttributeValue.fromS(value));
    }
  }
}
