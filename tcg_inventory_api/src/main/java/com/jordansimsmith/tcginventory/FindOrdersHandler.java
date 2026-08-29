package com.jordansimsmith.tcginventory;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.annotations.VisibleForTesting;
import com.jordansimsmith.dynamodb.Continuations;
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
  private static final int DEFAULT_LIMIT = 20;

  record OrderSummary(
      @JsonProperty("order_id") String orderId,
      @JsonProperty("state") String state,
      @JsonProperty("accepted_at") long acceptedAt,
      @JsonProperty("delivery_mode") @Nullable String deliveryMode,
      @JsonProperty("total_price") @Nullable String totalPrice,
      @JsonProperty("items_total_price") @Nullable String itemsTotalPrice,
      @JsonProperty("listed_total_price") @Nullable String listedTotalPrice,
      @JsonProperty("unit_count") int unitCount) {}

  record FindOrdersResponse(
      @JsonProperty("orders") List<OrderSummary> orders,
      @JsonProperty("next_continuation") @Nullable String nextContinuation) {}

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

    var queryParams = event.getQueryStringParameters();
    var continuation = queryParams != null ? queryParams.get("continuation") : null;
    var limitParam = queryParams != null ? queryParams.get("limit") : null;
    int limit = limitParam != null ? Integer.parseInt(limitParam) : DEFAULT_LIMIT;

    var queryConditional =
        QueryConditional.sortBeginsWith(
            Key.builder()
                .partitionValue(TcgInventoryItem.formatUserPk(user))
                .sortValue(TcgInventoryItem.ORDER_PREFIX)
                .build());

    var requestBuilder =
        QueryEnhancedRequest.builder()
            .queryConditional(queryConditional)
            .scanIndexForward(false)
            .limit(limit);

    if (continuation != null && !continuation.isEmpty()) {
      var exclusiveStartKey = Continuations.decode(continuation, objectMapper);
      if (exclusiveStartKey != null) {
        requestBuilder.exclusiveStartKey(exclusiveStartKey);
      }
    }

    var page = tcgInventoryTable.query(requestBuilder.build()).stream().findFirst().orElse(null);

    if (page == null) {
      return httpResponseFactory.ok(new FindOrdersResponse(List.of(), null));
    }

    var orders =
        page.items().stream()
            .map(
                item -> {
                  var orderLines = OrderLines.parse(item.getLines(), objectMapper);
                  var unitCount = 0;
                  for (var line : orderLines) {
                    unitCount += line.quantity();
                  }
                  return new OrderSummary(
                      item.getOrderId(),
                      item.getStatus(),
                      item.getCreatedAt() != null ? item.getCreatedAt().getEpochSecond() : 0,
                      item.getDeliveryMode(),
                      item.getTotalPrice(),
                      itemsTotalPrice(orderLines),
                      listedTotalPrice(orderLines),
                      unitCount);
                })
            .toList();

    var lastEvaluatedKey = page.lastEvaluatedKey();
    String nextContinuation = null;
    if (lastEvaluatedKey != null && !lastEvaluatedKey.isEmpty()) {
      nextContinuation = Continuations.encode(lastEvaluatedKey, objectMapper);
    }

    return httpResponseFactory.ok(new FindOrdersResponse(orders, nextContinuation));
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
