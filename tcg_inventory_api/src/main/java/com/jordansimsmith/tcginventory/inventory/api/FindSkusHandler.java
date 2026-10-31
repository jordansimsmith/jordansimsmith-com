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
import com.jordansimsmith.tcginventory.inventory.client.FindSkusRequest;
import com.jordansimsmith.tcginventory.inventory.client.InventoryService;
import java.util.Map;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class FindSkusHandler
    implements RequestHandler<APIGatewayV2HTTPEvent, APIGatewayV2HTTPResponse> {
  private static final Logger LOGGER = LoggerFactory.getLogger(FindSkusHandler.class);

  record ErrorResponse(@JsonProperty("message") String message) {}

  private final InventoryService inventoryService;
  private final RequestContextFactory requestContextFactory;
  private final HttpResponseFactory httpResponseFactory;

  public FindSkusHandler() {
    this(TcgInventoryFactory.create());
  }

  @VisibleForTesting
  public FindSkusHandler(TcgInventoryFactory factory) {
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
      LOGGER.error("error processing find skus request", e);
      throw new RuntimeException(e);
    }
  }

  private APIGatewayV2HTTPResponse doHandleRequest(APIGatewayV2HTTPEvent event)
      throws ServiceException {
    var ctx = requestContextFactory.createCtx(event);
    var queryParams = event.getQueryStringParameters();
    var game = value(queryParams, "game");
    var search = value(queryParams, "search");
    var continuation = value(queryParams, "continuation");
    var limitParam = value(queryParams, "limit");
    Integer limit = parseLimit(limitParam);
    if (limitParam != null && limit == null) {
      return httpResponseFactory.badRequest(new ErrorResponse("Invalid limit"));
    }
    return httpResponseFactory.ok(
        inventoryService.findSkus(ctx, new FindSkusRequest(game, search, limit, continuation)));
  }

  @Nullable
  private static String value(@Nullable Map<String, String> values, String key) {
    return values == null ? null : values.get(key);
  }

  @Nullable
  private Integer parseLimit(@Nullable String limitParam) {
    if (limitParam == null) {
      return null;
    }
    try {
      return Integer.valueOf(limitParam);
    } catch (NumberFormatException e) {
      return null;
    }
  }
}
