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
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;

public class UpdateUnitHandler
    implements RequestHandler<APIGatewayV2HTTPEvent, APIGatewayV2HTTPResponse> {

  private static final Logger LOGGER = LoggerFactory.getLogger(UpdateUnitHandler.class);

  record UpdateUnitRequest(@JsonProperty("condition") String condition) {}

  record UpdateUnitResponse(@JsonProperty("sku_id") String skuId) {}

  record ErrorResponse(@JsonProperty("message") String message) {}

  private final RequestContextFactory requestContextFactory;
  private final HttpResponseFactory httpResponseFactory;
  private final DynamoDbTable<TcgInventoryItem> tcgInventoryTable;
  private final TcgInventoryItemRepository tcgInventoryItemRepository;
  private final ObjectMapper objectMapper;

  public UpdateUnitHandler() {
    this(TcgInventoryFactory.create());
  }

  @VisibleForTesting
  UpdateUnitHandler(TcgInventoryFactory factory) {
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
      LOGGER.error("error processing update unit request", e);
      throw new RuntimeException(e);
    }
  }

  private APIGatewayV2HTTPResponse doHandleRequest(APIGatewayV2HTTPEvent event) {
    var user = requestContextFactory.createCtx(event).user();
    // api gateway rest proxy integrations pass path parameters still url-encoded
    var skuId = URLDecoder.decode(event.getPathParameters().get("sku_id"), StandardCharsets.UTF_8);
    var sequenceNumber = Integer.parseInt(event.getPathParameters().get("sequence_number"));

    UpdateUnitRequest body;
    try {
      body = objectMapper.readValue(event.getBody(), UpdateUnitRequest.class);
    } catch (Exception e) {
      return httpResponseFactory.badRequest(new ErrorResponse("invalid request body"));
    }

    try {
      if (body.condition() == null) {
        return httpResponseFactory.badRequest(new ErrorResponse("invalid condition"));
      }
      Condition.valueOf(body.condition());
    } catch (IllegalArgumentException e) {
      return httpResponseFactory.badRequest(new ErrorResponse("invalid condition"));
    }

    var sourceSkuPk = TcgInventoryItem.formatSkuPk(user, skuId);
    var unitSk = TcgInventoryItem.formatUnitSk(sequenceNumber);

    var skuItem =
        tcgInventoryTable.getItem(
            Key.builder()
                .partitionValue(sourceSkuPk)
                .sortValue(TcgInventoryItem.formatSkuSk())
                .build());
    if (skuItem == null) {
      return httpResponseFactory.notFound(new ErrorResponse("Not Found"));
    }

    var unitItem =
        tcgInventoryTable.getItem(
            Key.builder().partitionValue(sourceSkuPk).sortValue(unitSk).build());
    if (unitItem == null) {
      return httpResponseFactory.notFound(new ErrorResponse("Not Found"));
    }

    if (!"in_stock".equals(unitItem.getStatus())) {
      return httpResponseFactory.conflict(new ErrorResponse("unit is not in stock"));
    }

    if (body.condition().equals(skuItem.getCondition())) {
      return httpResponseFactory.conflict(new ErrorResponse("condition is unchanged"));
    }

    var targetSkuId =
        tcgInventoryItemRepository.updateUnitCondition(user, skuItem, unitItem, body.condition());

    return httpResponseFactory.ok(new UpdateUnitResponse(targetSkuId));
  }
}
