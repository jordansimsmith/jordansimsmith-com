package com.jordansimsmith.tcginventory.orders.server;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.model.Page;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryEnhancedRequest;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

public class OrderReadRepository {
  private final DynamoDbTable<OrderItem> orderTable;
  private final DynamoDbTable<OrderRowItem> rowTable;

  public OrderReadRepository(
      DynamoDbTable<OrderItem> orderTable, DynamoDbTable<OrderRowItem> rowTable) {
    this.orderTable = orderTable;
    this.rowTable = rowTable;
  }

  @Nullable
  public Page<OrderItem> findOrders(
      String user, int limit, @Nullable Map<String, AttributeValue> continuation) {
    var request =
        QueryEnhancedRequest.builder()
            .queryConditional(
                QueryConditional.sortBeginsWith(
                    Key.builder()
                        .partitionValue(OrderItem.formatPk(user))
                        .sortValue(OrderItem.ORDER_PREFIX)
                        .build()))
            .scanIndexForward(false)
            .limit(limit)
            .exclusiveStartKey(continuation)
            .build();
    return orderTable.query(request).stream().findFirst().orElse(null);
  }

  public List<OrderRowItem> findRows(String user, OrderItem order) {
    if (order.getRowCount() == null) {
      throw new IllegalStateException("order is missing row_count");
    }
    if (order.getRowCount() == 0) {
      return List.of();
    }

    var partitionKey = OrderRowItem.formatPk(user, order.getOrderId());
    var rows = new ArrayList<OrderRowItem>();
    var pages =
        rowTable.query(
            QueryEnhancedRequest.builder()
                .queryConditional(
                    QueryConditional.sortBetween(
                        Key.builder()
                            .partitionValue(partitionKey)
                            .sortValue(OrderRowItem.formatSk(0))
                            .build(),
                        Key.builder()
                            .partitionValue(partitionKey)
                            .sortValue(OrderRowItem.formatSk(order.getRowCount() - 1))
                            .build()))
                .consistentRead(true)
                .scanIndexForward(true)
                .build());
    pages.forEach(page -> rows.addAll(page.items()));
    if (rows.size() != order.getRowCount()) {
      throw new IllegalStateException("order rows are incomplete");
    }
    return rows;
  }

  @Nullable
  public OrderItem getOrder(String user, String orderId) {
    return orderTable.getItem(
        Key.builder()
            .partitionValue(OrderItem.formatPk(user))
            .sortValue(OrderItem.formatSk(orderId))
            .build());
  }
}
