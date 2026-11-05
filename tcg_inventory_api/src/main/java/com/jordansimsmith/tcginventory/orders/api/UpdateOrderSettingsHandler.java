package com.jordansimsmith.tcginventory.orders.api;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.annotations.VisibleForTesting;
import com.jordansimsmith.http.HttpResponseFactory;
import com.jordansimsmith.http.RequestContextFactory;
import com.jordansimsmith.service.ServiceException;
import com.jordansimsmith.tcginventory.TcgInventoryFactory;
import com.jordansimsmith.tcginventory.orders.client.OrderService;
import com.jordansimsmith.tcginventory.orders.client.UpdateOrderSettingsRequest;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class UpdateOrderSettingsHandler
    implements RequestHandler<APIGatewayV2HTTPEvent, APIGatewayV2HTTPResponse> {
  private static final Logger LOGGER = LoggerFactory.getLogger(UpdateOrderSettingsHandler.class);
  private static final String INVALID_REQUEST_BODY = "Invalid request body";

  private final ObjectMapper objectMapper;
  private final OrderService orderService;
  private final RequestContextFactory requestContextFactory;
  private final HttpResponseFactory httpResponseFactory;

  public UpdateOrderSettingsHandler() {
    this(TcgInventoryFactory.create());
  }

  @VisibleForTesting
  UpdateOrderSettingsHandler(TcgInventoryFactory factory) {
    this.objectMapper = factory.objectMapper();
    this.orderService = factory.orderService();
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
      LOGGER.error("error processing update order settings request", e);
      throw new RuntimeException(e);
    }
  }

  private APIGatewayV2HTTPResponse doHandleRequest(APIGatewayV2HTTPEvent event)
      throws ServiceException {
    var ctx = requestContextFactory.createCtx(event);
    var body = event.getBody();
    if (body == null || body.isBlank()) {
      return httpResponseFactory.badRequest(Map.of("message", INVALID_REQUEST_BODY));
    }

    UpdateOrderSettingsRequest request;
    try {
      request = objectMapper.readValue(body, UpdateOrderSettingsRequest.class);
    } catch (JsonProcessingException e) {
      return httpResponseFactory.badRequest(Map.of("message", INVALID_REQUEST_BODY));
    }
    if (request == null) {
      return httpResponseFactory.badRequest(Map.of("message", INVALID_REQUEST_BODY));
    }

    orderService.updateOrderSettings(ctx, request);
    return httpResponseFactory.noContent();
  }
}
