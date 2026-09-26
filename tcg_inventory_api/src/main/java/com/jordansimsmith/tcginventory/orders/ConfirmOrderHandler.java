package com.jordansimsmith.tcginventory.orders;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.annotations.VisibleForTesting;
import com.jordansimsmith.http.HttpResponseFactory;
import com.jordansimsmith.http.RequestContextFactory;
import com.jordansimsmith.tcginventory.TcgInventoryFactory;
import com.jordansimsmith.tcginventory.TcgInventoryTable;
import com.jordansimsmith.tcginventory.inventory.InventoryRepository;
import com.jordansimsmith.tcginventory.inventory.SkuItem;
import com.jordansimsmith.tcginventory.inventory.UnitItem;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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
  private final DynamoDbTable<OrderItem> orderTable;
  private final OrderRepository orderRepository;

  public ConfirmOrderHandler() {
    this(TcgInventoryFactory.create());
  }

  @VisibleForTesting
  ConfirmOrderHandler(TcgInventoryFactory factory) {
    this.requestContextFactory = factory.requestContextFactory();
    this.httpResponseFactory = factory.httpResponseFactory();
    this.orderTable = TcgInventoryTable.table(factory.dynamoDbEnhancedClient(), OrderItem.class);
    var inventoryRepository =
        new InventoryRepository(
            TcgInventoryTable.table(factory.dynamoDbEnhancedClient(), UnitItem.class),
            factory.dynamoDbClient(),
            factory.clock(),
            factory.ulidGenerator());
    this.orderRepository =
        new OrderRepository(
            this.orderTable, inventoryRepository, factory.dynamoDbClient(), factory.clock());
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
        Key.builder().partitionValue(SkuItem.formatUserPk(user)).sortValue(orderSk).build();

    var orderItem = orderTable.getItem(orderKey);
    if (orderItem == null) {
      return httpResponseFactory.notFound(new ErrorResponse("Not Found"));
    }

    if (!"to_pick".equals(orderItem.getStatus())) {
      return httpResponseFactory.conflict(new ErrorResponse("order is not ready to pick"));
    }

    var orderLines = orderItem.getLines();
    var soldUnits = new LinkedHashMap<String, List<Integer>>();
    for (var line : orderLines) {
      soldUnits
          .computeIfAbsent(line.getSkuId(), k -> new ArrayList<>())
          .addAll(line.getAllocatedSequenceNumbers());
    }

    var skuUnits =
        soldUnits.entrySet().stream()
            .map(entry -> new OrderRepository.SkuUnits(entry.getKey(), entry.getValue()))
            .toList();
    orderRepository.sellOrder(user, orderId, skuUnits);

    return httpResponseFactory.ok(new ConfirmOrderResponse(orderId, "fulfilled"));
  }
}
