package com.jordansimsmith.tcginventory.orders;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.annotations.VisibleForTesting;
import com.jordansimsmith.http.HttpResponseFactory;
import com.jordansimsmith.http.RequestContextFactory;
import com.jordansimsmith.queue.QueueClient;
import com.jordansimsmith.tcginventory.JobItem;
import com.jordansimsmith.tcginventory.JobMessage;
import com.jordansimsmith.tcginventory.TcgInventoryFactory;
import com.jordansimsmith.tcginventory.TcgInventoryTable;
import com.jordansimsmith.tcginventory.inventory.InventoryRepository;
import com.jordansimsmith.tcginventory.inventory.UnitItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;

public class ConfirmOrderHandler
    implements RequestHandler<APIGatewayV2HTTPEvent, APIGatewayV2HTTPResponse> {

  private static final Logger LOGGER = LoggerFactory.getLogger(ConfirmOrderHandler.class);

  record ConfirmOrderResponse(@JsonProperty("order_id") String orderId) {}

  record ErrorResponse(@JsonProperty("message") String message) {}

  private final RequestContextFactory requestContextFactory;
  private final HttpResponseFactory httpResponseFactory;
  private final OrderRepository orderRepository;
  private final QueueClient<JobMessage> jobsQueue;

  public ConfirmOrderHandler() {
    this(TcgInventoryFactory.create());
  }

  @VisibleForTesting
  ConfirmOrderHandler(TcgInventoryFactory factory) {
    this.requestContextFactory = factory.requestContextFactory();
    this.httpResponseFactory = factory.httpResponseFactory();
    var dynamoDbClient = factory.dynamoDbClient();
    var orderTable = TcgInventoryTable.table(factory.dynamoDbEnhancedClient(), OrderItem.class);
    var inventoryRepository =
        new InventoryRepository(
            TcgInventoryTable.table(factory.dynamoDbEnhancedClient(), UnitItem.class),
            dynamoDbClient,
            factory.clock(),
            factory.ulidGenerator());
    this.orderRepository =
        new OrderRepository(
            orderTable, factory.jobTable(), inventoryRepository, dynamoDbClient, factory.clock());
    this.jobsQueue = factory.jobsQueue();
  }

  @Override
  public APIGatewayV2HTTPResponse handleRequest(APIGatewayV2HTTPEvent event, Context context) {
    try {
      return doHandleRequest(event);
    } catch (Exception e) {
      LOGGER.error("error processing confirm order request", e);
      throw new RuntimeException(e);
    }
  }

  private APIGatewayV2HTTPResponse doHandleRequest(APIGatewayV2HTTPEvent event) {
    var user = requestContextFactory.createCtx(event).user();
    var orderId = event.getPathParameters().get("order_id");
    try {
      OrderItem.formatSk(orderId);
    } catch (IllegalArgumentException e) {
      return httpResponseFactory.notFound(new ErrorResponse("Not Found"));
    }

    var orderItem = orderRepository.getOrder(user, orderId);
    if (orderItem == null) {
      return httpResponseFactory.notFound(new ErrorResponse("Not Found"));
    }
    if ("fulfilled".equals(orderItem.getStatus())) {
      return httpResponseFactory.ok(new ConfirmOrderResponse(orderId));
    }

    try {
      if (!"to_pick".equals(orderItem.getStatus()) && !"fulfilling".equals(orderItem.getStatus())) {
        return httpResponseFactory.conflict(new ErrorResponse("order is not ready to pick"));
      }
      var job = orderRepository.startFulfillmentJob(user, orderId);
      if (job == null) {
        return httpResponseFactory.ok(new ConfirmOrderResponse(orderId));
      }
      return enqueue(user, orderId, job);
    } catch (TransactionCanceledException e) {
      var currentOrder = orderRepository.getOrder(user, orderId);
      if (currentOrder != null && "fulfilled".equals(currentOrder.getStatus())) {
        return httpResponseFactory.ok(new ConfirmOrderResponse(orderId));
      }
      throw e;
    }
  }

  private APIGatewayV2HTTPResponse enqueue(String user, String orderId, JobItem job) {
    var message = new JobMessage(user, job.getJobId(), "fulfill_order");
    var continuation = job.getContinuation() == null ? 0 : job.getContinuation();
    jobsQueue.send(message, user, message.deduplicationId(continuation));
    return httpResponseFactory.accepted(new ConfirmOrderResponse(orderId));
  }
}
