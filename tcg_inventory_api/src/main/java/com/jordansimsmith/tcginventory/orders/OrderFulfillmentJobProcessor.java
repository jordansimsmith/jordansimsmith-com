package com.jordansimsmith.tcginventory.orders;

import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.service.ServiceException;
import com.jordansimsmith.tcginventory.JobItem;
import com.jordansimsmith.tcginventory.JobProcessor;
import com.jordansimsmith.tcginventory.inventory.client.InventoryService;
import com.jordansimsmith.tcginventory.inventory.client.SellUnitRequest;
import java.util.ArrayList;
import java.util.HashSet;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;

public class OrderFulfillmentJobProcessor implements JobProcessor {
  private record AllocatedUnit(String skuId, int sequenceNumber) {}

  private final DynamoDbTable<OrderItem> orderTable;
  private final InventoryService inventoryService;
  private final OrderRepository orderRepository;

  public OrderFulfillmentJobProcessor(
      DynamoDbTable<OrderItem> orderTable,
      InventoryService inventoryService,
      OrderRepository orderRepository) {
    this.orderTable = orderTable;
    this.inventoryService = inventoryService;
    this.orderRepository = orderRepository;
  }

  @Override
  public SuccessJobResult processBatch(String user, JobItem job) throws ServiceException {
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
      return new SuccessJobResult(0, null);
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

    var ctx = new RequestContext(user);
    for (var unit : allocated) {
      inventoryService.sellUnit(
          ctx, new SellUnitRequest(unit.skuId(), unit.sequenceNumber(), orderId));
    }
    orderRepository.finishFulfilling(user, orderId);
    return new SuccessJobResult(allocated.size(), null);
  }
}
