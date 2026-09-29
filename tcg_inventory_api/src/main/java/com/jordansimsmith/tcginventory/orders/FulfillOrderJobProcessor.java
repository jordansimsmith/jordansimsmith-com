package com.jordansimsmith.tcginventory.orders;

import com.jordansimsmith.tcginventory.BatchResult;
import com.jordansimsmith.tcginventory.JobItem;

public class FulfillOrderJobProcessor {
  private final OrderRepository orderRepository;

  public FulfillOrderJobProcessor(OrderRepository orderRepository) {
    this.orderRepository = orderRepository;
  }

  public BatchResult processBatch(String user, JobItem jobItem) {
    var orderId = jobItem.getOrderId();
    if (orderId == null) {
      throw new IllegalStateException("order fulfillment job is missing its order id");
    }
    var order = orderRepository.getOrder(user, orderId);
    if (order == null) {
      throw new IllegalStateException("order not found for fulfillment job");
    }
    orderRepository.sellOrder(user, order);
    return new BatchResult(1, true);
  }
}
