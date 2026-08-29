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
  private final DynamoDbTable<TcgInventoryItem> tcgInventoryTable;
  private final TcgInventoryItemRepository tcgInventoryItemRepository;
  private final ObjectMapper objectMapper;

  public ConfirmOrderHandler() {
    this(TcgInventoryFactory.create());
  }

  @VisibleForTesting
  ConfirmOrderHandler(TcgInventoryFactory factory) {
    this.requestContextFactory = factory.requestContextFactory();
    this.httpResponseFactory = factory.httpResponseFactory();
    this.tcgInventoryTable = factory.tcgInventoryTable();
    this.tcgInventoryItemRepository = factory.tcgInventoryItemRepository();
    this.objectMapper = factory.objectMapper();
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

    var orderKey =
        Key.builder()
            .partitionValue(TcgInventoryItem.formatUserPk(user))
            .sortValue(TcgInventoryItem.formatOrderSk(orderId))
            .build();

    var orderItem = tcgInventoryTable.getItem(orderKey);
    if (orderItem == null) {
      return httpResponseFactory.notFound(new ErrorResponse("Not Found"));
    }

    if (!"to_pick".equals(orderItem.getStatus())) {
      return httpResponseFactory.conflict(new ErrorResponse("order is not ready to pick"));
    }

    var orderLines = OrderLines.parse(orderItem.getLines(), objectMapper);
    var soldUnits = new LinkedHashMap<String, List<Integer>>();
    for (var line : orderLines) {
      soldUnits
          .computeIfAbsent(line.skuId(), k -> new ArrayList<>())
          .addAll(line.allocatedSequenceNumbers());
    }

    var skuUnits =
        soldUnits.entrySet().stream()
            .map(entry -> new TcgInventoryItemRepository.SkuUnits(entry.getKey(), entry.getValue()))
            .toList();
    tcgInventoryItemRepository.sellOrder(user, orderId, skuUnits);

    return httpResponseFactory.ok(new ConfirmOrderResponse(orderId, "fulfilled"));
  }
}
