package com.jordansimsmith.tcginventory.orders;

import com.jordansimsmith.tcginventory.JobItem;
import com.jordansimsmith.tcginventory.TcgInventoryTable;
import com.jordansimsmith.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
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
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;

public class OrderRepository {
  private record AllocatedUnit(String skuId, int sequenceNumber) {}

  private final DynamoDbTable<JobItem> jobTable;
  private final DynamoDbTable<OrderRowItem> rowTable;
  private final DynamoDbClient dynamoDbClient;
  private final Clock clock;

  public OrderRepository(
      DynamoDbTable<OrderRowItem> rowTable,
      DynamoDbTable<JobItem> jobTable,
      DynamoDbClient dynamoDbClient,
      Clock clock) {
    this.rowTable = rowTable;
    this.jobTable = jobTable;
    this.dynamoDbClient = dynamoDbClient;
    this.clock = clock;
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
}
