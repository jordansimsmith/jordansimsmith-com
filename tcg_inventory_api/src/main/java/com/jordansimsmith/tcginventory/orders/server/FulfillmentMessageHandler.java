package com.jordansimsmith.tcginventory.orders.server;

import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.service.ServiceException;
import com.jordansimsmith.tcginventory.inventory.client.InventoryService;
import com.jordansimsmith.tcginventory.inventory.client.SellUnitRequest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

public class FulfillmentMessageHandler {
  private record AllocatedUnit(String skuId, int sequenceNumber) {}

  private final OrderReadRepository orderReadRepository;
  private final OrderMutationRepository orderMutationRepository;
  private final InventoryService inventoryService;

  public FulfillmentMessageHandler(
      OrderReadRepository orderReadRepository,
      OrderMutationRepository orderMutationRepository,
      InventoryService inventoryService) {
    this.orderReadRepository = orderReadRepository;
    this.orderMutationRepository = orderMutationRepository;
    this.inventoryService = inventoryService;
  }

  public void handleMessage(RequestContext ctx, FulfillmentMessage message)
      throws ServiceException {
    var order = loadOrder(ctx.user(), message.orderId());
    if ("fulfilled".equals(order.getStatus())) {
      return;
    }
    if (!"fulfilling".equals(order.getStatus())) {
      throw new IllegalStateException("order is not fulfilling");
    }

    var allocations = validateAllocations(orderReadRepository.findRows(ctx.user(), order));
    sellAllocations(ctx, message.orderId(), allocations);
    orderMutationRepository.finishFulfilling(ctx.user(), message.orderId());
  }

  private OrderItem loadOrder(String user, String orderId) {
    var order = orderReadRepository.getOrder(user, orderId);
    if (order == null) {
      throw new IllegalStateException("fulfillment order not found");
    }
    return order;
  }

  private List<AllocatedUnit> validateAllocations(List<OrderRowItem> rows) {
    if (rows.isEmpty()) {
      throw new IllegalStateException("order has no lines to fulfill");
    }

    var allocations = new ArrayList<AllocatedUnit>();
    for (var row : rows) {
      if (row.getQuantity() <= 0 || row.getAllocatedSequenceNumbers().size() != row.getQuantity()) {
        throw new IllegalStateException("order line is not fully allocated");
      }
      for (var sequenceNumber : row.getAllocatedSequenceNumbers()) {
        allocations.add(new AllocatedUnit(row.getSkuId(), sequenceNumber));
      }
    }

    if (allocations.size() != new HashSet<>(allocations).size()) {
      throw new IllegalStateException("order allocation contains a duplicate unit");
    }
    return allocations;
  }

  private void sellAllocations(RequestContext ctx, String orderId, List<AllocatedUnit> allocations)
      throws ServiceException {
    for (var allocation : allocations) {
      inventoryService.sellUnit(
          ctx, new SellUnitRequest(allocation.skuId(), allocation.sequenceNumber(), orderId));
    }
  }
}
