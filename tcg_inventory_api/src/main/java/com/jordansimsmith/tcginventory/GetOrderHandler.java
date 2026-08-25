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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;

public class GetOrderHandler
    implements RequestHandler<APIGatewayV2HTTPEvent, APIGatewayV2HTTPResponse> {

  private static final Logger LOGGER = LoggerFactory.getLogger(GetOrderHandler.class);

  record OrderUnitResponse(
      @JsonProperty("sequence_number") int sequenceNumber,
      @JsonProperty("location") String location,
      @JsonProperty("name") String name,
      @JsonProperty("set_code") String setCode,
      @JsonProperty("collector_number") String collectorNumber,
      @JsonProperty("finish") String finish,
      @JsonProperty("condition") String condition) {}

  record OrderLineResponse(
      @JsonProperty("name") String name,
      @JsonProperty("set_code") String setCode,
      @JsonProperty("collector_number") String collectorNumber,
      @JsonProperty("finish") String finish,
      @JsonProperty("condition") String condition,
      @JsonProperty("quantity") int quantity,
      @JsonProperty("price") @Nullable String price,
      @JsonProperty("listed_price") @Nullable String listedPrice) {}

  record OrderDetailResponse(
      @JsonProperty("order_id") String orderId,
      @JsonProperty("state") String state,
      @JsonProperty("accepted_at") long acceptedAt,
      @JsonProperty("delivery_mode") @Nullable String deliveryMode,
      @JsonProperty("total_price") @Nullable String totalPrice,
      @JsonProperty("lines") List<OrderLineResponse> lines,
      @JsonProperty("units") List<OrderUnitResponse> units) {}

  record ErrorResponse(@JsonProperty("message") String message) {}

  private final RequestContextFactory requestContextFactory;
  private final HttpResponseFactory httpResponseFactory;
  private final DynamoDbTable<TcgInventoryItem> tcgInventoryTable;
  private final ObjectMapper objectMapper;

  public GetOrderHandler() {
    this(TcgInventoryFactory.create());
  }

  @VisibleForTesting
  GetOrderHandler(TcgInventoryFactory factory) {
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
      LOGGER.error("error processing get order request", e);
      throw new RuntimeException(e);
    }
  }

  private APIGatewayV2HTTPResponse doHandleRequest(APIGatewayV2HTTPEvent event) {
    var user = requestContextFactory.createCtx(event).user();
    var orderId = event.getPathParameters().get("order_id");

    var orderKey =
        Key.builder()
            .partitionValue(TcgInventoryItem.formatUserPk(user))
            .sortValue(TcgInventoryItem.formatOrderSk(orderId))
            .build();

    var orderItem = tcgInventoryTable.getItem(orderKey);
    if (orderItem == null) {
      return httpResponseFactory.notFound(new ErrorResponse("Not Found"));
    }

    var orderLines = OrderLines.parse(orderItem.getLines(), objectMapper);

    Map<String, TcgInventoryItem> skuCache = new HashMap<>();
    var units = new ArrayList<OrderUnitResponse>();
    var lines = new ArrayList<OrderLineResponse>();

    for (var line : orderLines) {
      var skuItem =
          skuCache.computeIfAbsent(
              line.skuId(),
              skuId ->
                  tcgInventoryTable.getItem(
                      Key.builder()
                          .partitionValue(TcgInventoryItem.formatSkuPk(user, skuId))
                          .sortValue(TcgInventoryItem.formatSkuSk())
                          .build()));

      if (skuItem == null) {
        continue;
      }

      lines.add(
          new OrderLineResponse(
              skuItem.getName(),
              skuItem.getSetCode(),
              skuItem.getCollectorNumber(),
              skuItem.getFinish(),
              skuItem.getCondition(),
              line.quantity(),
              line.price(),
              line.listedPrice()));

      for (var seqNum : line.allocatedSequenceNumbers()) {
        units.add(
            new OrderUnitResponse(
                seqNum,
                InventoryLocation.formatLocation(seqNum),
                skuItem.getName(),
                skuItem.getSetCode(),
                skuItem.getCollectorNumber(),
                skuItem.getFinish(),
                skuItem.getCondition()));
      }
    }

    units.sort(Comparator.comparingInt(OrderUnitResponse::sequenceNumber));

    return httpResponseFactory.ok(
        new OrderDetailResponse(
            orderItem.getOrderId(),
            orderItem.getStatus(),
            orderItem.getCreatedAt() != null ? orderItem.getCreatedAt().getEpochSecond() : 0,
            orderItem.getDeliveryMode(),
            orderItem.getTotalPrice(),
            lines,
            units));
  }
}
