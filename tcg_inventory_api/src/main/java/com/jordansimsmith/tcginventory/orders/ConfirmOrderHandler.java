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
import com.jordansimsmith.tcginventory.ActiveJob;
import com.jordansimsmith.tcginventory.JobItem;
import com.jordansimsmith.tcginventory.JobMessage;
import com.jordansimsmith.tcginventory.TcgInventoryFactory;
import com.jordansimsmith.tcginventory.TcgInventoryTable;
import com.jordansimsmith.tcginventory.inventory.InventoryRepository;
import com.jordansimsmith.tcginventory.inventory.UnitItem;
import com.jordansimsmith.time.Clock;
import com.jordansimsmith.ulid.UlidGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;

public class ConfirmOrderHandler
    implements RequestHandler<APIGatewayV2HTTPEvent, APIGatewayV2HTTPResponse> {

  private static final Logger LOGGER = LoggerFactory.getLogger(ConfirmOrderHandler.class);

  record ConfirmOrderResponse(
      @JsonProperty("order_id") String orderId, @JsonProperty("state") String state) {}

  record ErrorResponse(@JsonProperty("message") String message) {}

  private final RequestContextFactory requestContextFactory;
  private final HttpResponseFactory httpResponseFactory;
  private final ActiveJob activeJob;
  private final DynamoDbTable<OrderItem> orderTable;
  private final OrderRepository orderRepository;
  private final QueueClient<JobMessage> jobsQueue;
  private final Clock clock;
  private final UlidGenerator ulidGenerator;

  public ConfirmOrderHandler() {
    this(TcgInventoryFactory.create());
  }

  @VisibleForTesting
  ConfirmOrderHandler(TcgInventoryFactory factory) {
    this.requestContextFactory = factory.requestContextFactory();
    this.httpResponseFactory = factory.httpResponseFactory();
    this.activeJob = new ActiveJob(factory.jobTable());
    this.orderTable = TcgInventoryTable.table(factory.dynamoDbEnhancedClient(), OrderItem.class);
    var jobTable = factory.jobTable();
    this.jobsQueue = factory.jobsQueue();
    this.clock = factory.clock();
    this.ulidGenerator = factory.ulidGenerator();
    var inventoryRepository =
        new InventoryRepository(
            TcgInventoryTable.table(factory.dynamoDbEnhancedClient(), UnitItem.class),
            factory.dynamoDbClient(),
            factory.clock(),
            factory.ulidGenerator());
    this.orderRepository =
        new OrderRepository(
            this.orderTable, jobTable, inventoryRepository, factory.dynamoDbClient(), this.clock);
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

    String orderSk;
    try {
      orderSk = OrderItem.formatSk(orderId);
    } catch (IllegalArgumentException e) {
      return httpResponseFactory.notFound(new ErrorResponse("Not Found"));
    }

    var orderKey =
        Key.builder().partitionValue(OrderItem.formatPk(user)).sortValue(orderSk).build();

    var orderItem = orderTable.getItem(request -> request.key(orderKey).consistentRead(true));
    if (orderItem == null) {
      return httpResponseFactory.notFound(new ErrorResponse("Not Found"));
    }

    if ("fulfilled".equals(orderItem.getStatus())) {
      return httpResponseFactory.ok(new ConfirmOrderResponse(orderId, "fulfilled"));
    }
    if ("fulfilling".equals(orderItem.getStatus())) {
      return httpResponseFactory.accepted(new ConfirmOrderResponse(orderId, "fulfilling"));
    }
    if (!"to_pick".equals(orderItem.getStatus())) {
      return httpResponseFactory.conflict(new ErrorResponse("order is not ready to pick"));
    }

    // keep inventory consistent while a background job is running.
    if (activeJob.exists(user)) {
      return httpResponseFactory.conflict(new ErrorResponse("another job is in progress"));
    }

    var job =
        JobItem.create(
            user, ulidGenerator.generate(), "order_fulfillment", null, orderId, clock.now());
    orderRepository.startFulfilling(user, orderItem, job);
    var message = new JobMessage(user, job.getJobId(), "order_fulfillment");
    jobsQueue.send(message, user, message.deduplicationId(0));
    return httpResponseFactory.accepted(new ConfirmOrderResponse(orderId, "fulfilling"));
  }
}
