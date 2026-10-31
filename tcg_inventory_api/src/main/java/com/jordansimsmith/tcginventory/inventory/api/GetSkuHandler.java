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
import com.jordansimsmith.tcginventory.inventory.client.GetSkuRequest;
import com.jordansimsmith.tcginventory.inventory.client.InventoryService;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GetSkuHandler
    implements RequestHandler<APIGatewayV2HTTPEvent, APIGatewayV2HTTPResponse> {
  private static final Logger LOGGER = LoggerFactory.getLogger(GetSkuHandler.class);

  record ErrorResponse(@JsonProperty("message") String message) {}

  private final InventoryService inventoryService;
  private final RequestContextFactory requestContextFactory;
  private final HttpResponseFactory httpResponseFactory;

  public GetSkuHandler() {
    this(TcgInventoryFactory.create());
  }

  @VisibleForTesting
  public GetSkuHandler(TcgInventoryFactory factory) {
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
      LOGGER.error("error processing get sku request", e);
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
    return httpResponseFactory.ok(inventoryService.getSku(ctx, new GetSkuRequest(skuId)));
  }
}
