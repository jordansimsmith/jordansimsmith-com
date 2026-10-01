package com.jordansimsmith.tcginventory.orders;

import com.jordansimsmith.tcginventory.JobItem;
import com.jordansimsmith.tcginventory.JobProcessor;
import com.jordansimsmith.tcginventory.TcgInventoryFactory;
import com.jordansimsmith.tcginventory.TcgInventoryTable;
import com.jordansimsmith.tcginventory.inventory.InventoryRepository;
import com.jordansimsmith.tcginventory.inventory.UnitItem;
import java.util.ArrayList;
import java.util.HashSet;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;

public class OrderFulfillmentJobProcessor implements JobProcessor {
  private record AllocatedUnit(String skuId, int sequenceNumber) {}

  private final DynamoDbTable<OrderItem> orderTable;
  private final InventoryRepository inventoryRepository;
  private final OrderRepository orderRepository;

  public OrderFulfillmentJobProcessor(TcgInventoryFactory factory) {
    var enhancedClient = factory.dynamoDbEnhancedClient();
    this.orderTable = TcgInventoryTable.table(enhancedClient, OrderItem.class);
    this.inventoryRepository =
        new InventoryRepository(
            TcgInventoryTable.table(enhancedClient, UnitItem.class),
            factory.dynamoDbClient(),
            factory.clock(),
            factory.ulidGenerator());
    this.orderRepository =
        new OrderRepository(
            orderTable,
            factory.jobTable(),
            inventoryRepository,
            factory.dynamoDbClient(),
            factory.clock());
  }

  @Override
  public SuccessJobResult processBatch(String user, JobItem job) {
    var orderId = job.getOrderId();
    if (orderId == null) {
      throw new IllegalStateException("order fulfillment job is missing order_id");
    }

    var order =
        orderTable.getItem(
            request ->
                request
                    .key(
                        Key.builder()
                            .partitionValue(OrderItem.formatPk(user))
                            .sortValue(OrderItem.formatSk(orderId))
                            .build())
                    .consistentRead(true));
    if (order == null) {
      throw new IllegalStateException("fulfillment order not found");
    }
    if ("fulfilled".equals(order.getStatus())) {
      return new SuccessJobResult(0, true);
    }
    if (!"fulfilling".equals(order.getStatus())) {
      throw new IllegalStateException("order is not fulfilling");
    }
    if (order.getLines().isEmpty()) {
      throw new IllegalStateException("order has no lines to fulfill");
    }

    var allocated = new ArrayList<AllocatedUnit>();
    for (var line : order.getLines()) {
      if (line.getQuantity() <= 0
          || line.getAllocatedSequenceNumbers().size() != line.getQuantity()) {
        throw new IllegalStateException("order line is not fully allocated");
      }
      for (var sequenceNumber : line.getAllocatedSequenceNumbers()) {
        allocated.add(new AllocatedUnit(line.getSkuId(), sequenceNumber));
      }
    }
    if (allocated.size() != new HashSet<>(allocated).size()) {
      throw new IllegalStateException("order allocation contains a duplicate unit");
    }

    for (var unit : allocated) {
      inventoryRepository.updateUnitForSale(user, orderId, unit.skuId(), unit.sequenceNumber());
    }
    orderRepository.finishFulfilling(user, orderId);
    return new SuccessJobResult(allocated.size(), true);
  }
}
