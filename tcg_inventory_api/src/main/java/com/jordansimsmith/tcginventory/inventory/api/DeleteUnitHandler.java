package com.jordansimsmith.tcginventory.inventory.api;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.annotations.VisibleForTesting;
import com.jordansimsmith.http.HttpResponseFactory;
import com.jordansimsmith.http.RequestContextFactory;
import com.jordansimsmith.service.ServiceException;
import com.jordansimsmith.tcginventory.TcgInventoryFactory;
import com.jordansimsmith.tcginventory.inventory.client.DeleteUnitRequest;
import com.jordansimsmith.tcginventory.inventory.client.InventoryService;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DeleteUnitHandler
    implements RequestHandler<APIGatewayV2HTTPEvent, APIGatewayV2HTTPResponse> {
  private static final Logger LOGGER = LoggerFactory.getLogger(DeleteUnitHandler.class);

  record ErrorResponse(@JsonProperty("message") String message) {}

  private final InventoryService inventoryService;
  private final RequestContextFactory requestContextFactory;
  private final HttpResponseFactory httpResponseFactory;

  public DeleteUnitHandler() {
    this(TcgInventoryFactory.create());
  }

  @VisibleForTesting
  public DeleteUnitHandler(TcgInventoryFactory factory) {
    this.inventoryService = factory.inventoryService();
    this.requestContextFactory = factory.requestContextFactory();
    this.httpResponseFactory = factory.httpResponseFactory();
  }

  @Override
  public APIGatewayV2HTTPResponse handleRequest(APIGatewayV2HTTPEvent event, Context context) {
    try {
      return doHandleRequest(event);
    } catch (ServiceException e) {
      return httpResponseFactory.serviceError(e);
    } catch (Exception e) {
      LOGGER.error("error processing delete unit request", e);
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

    inventoryService.deleteUnit(
        requestContextFactory.createCtx(event),
        new DeleteUnitRequest(
            skuId, sequenceNumber, value(event.getQueryStringParameters(), "reason")));
    return httpResponseFactory.noContent();
  }

  @Nullable
  private static String value(@Nullable Map<String, String> values, String key) {
    return values == null ? null : values.get(key);
  }
}
