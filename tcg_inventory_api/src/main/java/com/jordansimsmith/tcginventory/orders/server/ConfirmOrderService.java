package com.jordansimsmith.tcginventory.orders.server;

import com.jordansimsmith.queue.QueueClient;
import com.jordansimsmith.service.BadRequestException;
import com.jordansimsmith.service.ConflictException;
import com.jordansimsmith.service.NotFoundException;
import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.service.ServiceException;
import com.jordansimsmith.tcginventory.inventory.client.GetUnitRequest;
import com.jordansimsmith.tcginventory.inventory.client.InventoryService;
import com.jordansimsmith.tcginventory.inventory.client.UnitStatus;
import com.jordansimsmith.tcginventory.orders.client.ConfirmOrderRequest;
import com.jordansimsmith.tcginventory.orders.client.ConfirmOrderResponse;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

public class ConfirmOrderService {
  private record AllocatedUnit(String skuId, int sequenceNumber) {}

  private final OrderReadRepository orderReadRepository;
  private final OrderMutationRepository orderMutationRepository;
  private final InventoryService inventoryService;
  private final QueueClient<FulfillmentMessage> ordersQueue;

  public ConfirmOrderService(
      OrderReadRepository orderReadRepository,
      OrderMutationRepository orderMutationRepository,
      InventoryService inventoryService,
      QueueClient<FulfillmentMessage> ordersQueue) {
    this.orderReadRepository = orderReadRepository;
    this.orderMutationRepository = orderMutationRepository;
    this.inventoryService = inventoryService;
    this.ordersQueue = ordersQueue;
  }

  public ConfirmOrderResponse confirmOrder(RequestContext ctx, ConfirmOrderRequest request)
      throws ServiceException {
    var orderId = request.orderId();
    var order = loadOrder(ctx.user(), orderId);
    if ("fulfilling".equals(order.getStatus()) || "fulfilled".equals(order.getStatus())) {
      return new ConfirmOrderResponse();
    }
    if (!"to_pick".equals(order.getStatus())) {
      throw new ConflictException("order is not ready to pick");
    }

    var allocations = validateAllocations(orderReadRepository.findRows(ctx.user(), order));
    checkOwnership(ctx, orderId, allocations);

    orderMutationRepository.startFulfilling(ctx.user(), orderId);
    ordersQueue.send(new FulfillmentMessage(ctx.user(), orderId), orderId, orderId);
    return new ConfirmOrderResponse();
  }

  private OrderItem loadOrder(String user, String orderId) throws ServiceException {
    if (orderId == null || orderId.isBlank()) {
      throw new BadRequestException("order_id is required");
    }
    var order = orderReadRepository.getOrder(user, orderId);
    if (order == null) {
      throw new NotFoundException("Not Found");
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

  private void checkOwnership(RequestContext ctx, String orderId, List<AllocatedUnit> allocations)
      throws ServiceException {
    for (var allocation : allocations) {
      var unit =
          inventoryService
              .getUnit(ctx, new GetUnitRequest(allocation.skuId(), allocation.sequenceNumber()))
              .unit();
      if (unit.status() != UnitStatus.RESERVED || !orderId.equals(unit.reservationId())) {
        throw new IllegalStateException("order has an unexpected allocated unit");
      }
    }
  }
}
