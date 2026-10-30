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
import com.jordansimsmith.tcginventory.inventory.client.FindUnitsRequest;
import com.jordansimsmith.tcginventory.inventory.client.InventoryService;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class FindUnitsHandler
    implements RequestHandler<APIGatewayV2HTTPEvent, APIGatewayV2HTTPResponse> {
  private static final Logger LOGGER = LoggerFactory.getLogger(FindUnitsHandler.class);

  record ErrorResponse(@JsonProperty("message") String message) {}

  private final InventoryService inventoryService;
  private final RequestContextFactory requestContextFactory;
  private final HttpResponseFactory httpResponseFactory;

  public FindUnitsHandler() {
    this(TcgInventoryFactory.create());
  }

  @VisibleForTesting
  FindUnitsHandler(TcgInventoryFactory factory) {
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
      LOGGER.error("error processing find units request", e);
      throw new RuntimeException(e);
    }
  }

  private APIGatewayV2HTTPResponse doHandleRequest(APIGatewayV2HTTPEvent event)
      throws ServiceException {
    var ctx = requestContextFactory.createCtx(event);
    var pathParams = event.getPathParameters();
    var skuId = pathParams == null ? null : pathParams.get("sku_id");
    if (skuId == null) {
      return httpResponseFactory.badRequest(new ErrorResponse("sku_id is required"));
    }

    try {
      skuId = URLDecoder.decode(skuId, StandardCharsets.UTF_8);
    } catch (IllegalArgumentException e) {
      return httpResponseFactory.badRequest(new ErrorResponse("Invalid sku_id"));
    }

    var queryParams = event.getQueryStringParameters();
    var limitParam = queryParams == null ? null : queryParams.get("limit");
    Integer limit = parseLimit(limitParam);
    if (limitParam != null && limit == null) {
      return httpResponseFactory.badRequest(new ErrorResponse("Invalid limit"));
    }
    var continuation = queryParams == null ? null : queryParams.get("continuation");
    return httpResponseFactory.ok(
        inventoryService.findUnits(ctx, new FindUnitsRequest(skuId, limit, continuation)));
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
