package com.jordansimsmith.tcginventory;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.annotations.VisibleForTesting;
import com.jordansimsmith.http.HttpResponseFactory;
import com.jordansimsmith.http.RequestContextFactory;
import java.math.BigDecimal;
import java.util.List;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryEnhancedRequest;

public class FindOrdersHandler
    implements RequestHandler<APIGatewayV2HTTPEvent, APIGatewayV2HTTPResponse> {

  private static final Logger LOGGER = LoggerFactory.getLogger(FindOrdersHandler.class);

  record OrderSummary(
      @JsonProperty("order_id") String orderId,
      @JsonProperty("state") String state,
      @JsonProperty("accepted_at") long acceptedAt,
      @JsonProperty("delivery_mode") @Nullable String deliveryMode,
      @JsonProperty("total_price") @Nullable String totalPrice,
      @JsonProperty("items_total_price") @Nullable String itemsTotalPrice,
      @JsonProperty("listed_total_price") @Nullable String listedTotalPrice) {}

  record FindOrdersResponse(@JsonProperty("orders") List<OrderSummary> orders) {}

  private final RequestContextFactory requestContextFactory;
  private final HttpResponseFactory httpResponseFactory;
  private final DynamoDbTable<TcgInventoryItem> tcgInventoryTable;
  private final ObjectMapper objectMapper;

  public FindOrdersHandler() {
    this(TcgInventoryFactory.create());
  }

  @VisibleForTesting
  FindOrdersHandler(TcgInventoryFactory factory) {
    this.requestContextFactory = factory.requestContextFactory();
    this.httpResponseFactory = factory.httpResponseFactory();
    this.tcgInventoryTable = factory.tcgInventoryTable();
    this.objectMapper = factory.objectMapper();
  }

  @Override
  public APIGatewayV2HTTPResponse handleRequest(APIGatewayV2HTTPEvent event, Context context) {
    try {
      return doHandleRequest(event);
    } catch (Exception e) {
      LOGGER.error("error processing find orders request", e);
      throw new RuntimeException(e);
    }
  }

  private APIGatewayV2HTTPResponse doHandleRequest(APIGatewayV2HTTPEvent event) {
    var user = requestContextFactory.createCtx(event).user();

    var queryConditional =
        QueryConditional.sortBeginsWith(
            Key.builder()
                .partitionValue(TcgInventoryItem.formatUserPk(user))
                .sortValue(TcgInventoryItem.ORDER_PREFIX)
                .build());

    var request =
        QueryEnhancedRequest.builder()
            .queryConditional(queryConditional)
            .scanIndexForward(false)
            .build();

    var orders =
        tcgInventoryTable.query(request).stream()
            .flatMap(page -> page.items().stream())
            .map(
                item -> {
                  var orderLines = OrderLines.parse(item.getLines(), objectMapper);
                  return new OrderSummary(
                      item.getOrderId(),
                      item.getStatus(),
                      item.getCreatedAt() != null ? item.getCreatedAt().getEpochSecond() : 0,
                      item.getDeliveryMode(),
                      item.getTotalPrice(),
                      itemsTotalPrice(orderLines),
                      listedTotalPrice(orderLines));
                })
            .toList();

    return httpResponseFactory.ok(new FindOrdersResponse(orders));
  }

  private static @Nullable String itemsTotalPrice(List<OrderLines.OrderLine> orderLines) {
    if (orderLines.isEmpty()) {
      return null;
    }
    return OrderLines.itemsTotal(orderLines).toPlainString();
  }

  private static @Nullable String listedTotalPrice(List<OrderLines.OrderLine> orderLines) {
    if (orderLines.isEmpty()) {
      return null;
    }
    var total = BigDecimal.ZERO;
    for (var line : orderLines) {
      if (line.listedPrice() == null) {
        return null;
      }
      total =
          total.add(
              new BigDecimal(line.listedPrice()).multiply(BigDecimal.valueOf(line.quantity())));
    }
    return total.toPlainString();
  }
}
