package com.jordansimsmith.tcginventory.inventory.api;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.annotations.VisibleForTesting;
import com.jordansimsmith.http.HttpResponseFactory;
import com.jordansimsmith.http.RequestContextFactory;
import com.jordansimsmith.service.ServiceException;
import com.jordansimsmith.tcginventory.TcgInventoryFactory;
import com.jordansimsmith.tcginventory.inventory.client.Condition;
import com.jordansimsmith.tcginventory.inventory.client.InventoryService;
import com.jordansimsmith.tcginventory.inventory.client.UpdateUnitRequest;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class UpdateUnitHandler
    implements RequestHandler<APIGatewayV2HTTPEvent, APIGatewayV2HTTPResponse> {
  private static final Logger LOGGER = LoggerFactory.getLogger(UpdateUnitHandler.class);

  private record Body(@JsonProperty("condition") Condition condition) {}

  private record ErrorResponse(@JsonProperty("message") String message) {}

  private final InventoryService inventoryService;
  private final RequestContextFactory requestContextFactory;
  private final HttpResponseFactory httpResponseFactory;
  private final ObjectMapper objectMapper;

  public UpdateUnitHandler() {
    this(TcgInventoryFactory.create());
  }

  @VisibleForTesting
  public UpdateUnitHandler(TcgInventoryFactory factory) {
    inventoryService = factory.inventoryService();
    requestContextFactory = factory.requestContextFactory();
    httpResponseFactory = factory.httpResponseFactory();
    objectMapper = factory.objectMapper();
  }

  @Override
  public APIGatewayV2HTTPResponse handleRequest(APIGatewayV2HTTPEvent event, Context context) {
    try {
      return doHandleRequest(event);
    } catch (ServiceException e) {
      return httpResponseFactory.serviceError(e);
    } catch (Exception e) {
      LOGGER.error("error processing update unit request", e);
      throw new RuntimeException(e);
    }
  }

  private APIGatewayV2HTTPResponse doHandleRequest(APIGatewayV2HTTPEvent event)
      throws ServiceException {
    String skuId;
    try {
      skuId = URLDecoder.decode(event.getPathParameters().get("sku_id"), StandardCharsets.UTF_8);
    } catch (IllegalArgumentException e) {
      return httpResponseFactory.badRequest(new ErrorResponse("Invalid sku_id"));
    }
    int sequenceNumber;
    try {
      sequenceNumber = Integer.parseInt(event.getPathParameters().get("sequence_number"));
    } catch (NumberFormatException e) {
      return httpResponseFactory.badRequest(new ErrorResponse("Invalid sequence_number"));
    }
    Body body;
    try {
      body = objectMapper.readValue(event.getBody(), Body.class);
    } catch (Exception e) {
      return httpResponseFactory.badRequest(new ErrorResponse("Invalid request body"));
    }
    var response =
        inventoryService.updateUnit(
            requestContextFactory.createCtx(event),
            new UpdateUnitRequest(skuId, sequenceNumber, body.condition()));
    return httpResponseFactory.ok(response);
  }
}
