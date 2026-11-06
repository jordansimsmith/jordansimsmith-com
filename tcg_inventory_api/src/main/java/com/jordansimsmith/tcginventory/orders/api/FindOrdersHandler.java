package com.jordansimsmith.tcginventory.orders.api;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import com.google.common.annotations.VisibleForTesting;
import com.jordansimsmith.http.HttpResponseFactory;
import com.jordansimsmith.http.RequestContextFactory;
import com.jordansimsmith.service.ServiceException;
import com.jordansimsmith.tcginventory.TcgInventoryFactory;
import com.jordansimsmith.tcginventory.orders.client.FindOrdersRequest;
import com.jordansimsmith.tcginventory.orders.client.OrderService;
import java.util.Map;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class FindOrdersHandler
    implements RequestHandler<APIGatewayV2HTTPEvent, APIGatewayV2HTTPResponse> {
  private static final Logger LOGGER = LoggerFactory.getLogger(FindOrdersHandler.class);

  private final OrderService orderService;
  private final RequestContextFactory requestContextFactory;
  private final HttpResponseFactory httpResponseFactory;

  public FindOrdersHandler() {
    this(TcgInventoryFactory.create());
  }

  @VisibleForTesting
  public FindOrdersHandler(TcgInventoryFactory factory) {
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
      LOGGER.error("error processing find orders request", e);
      throw new RuntimeException(e);
    }
  }

  private APIGatewayV2HTTPResponse doHandleRequest(APIGatewayV2HTTPEvent event)
      throws ServiceException {
    var queryParams = event.getQueryStringParameters();
    var limitParam = value(queryParams, "limit");
    Integer limit = parseLimit(limitParam);
    if (limitParam != null && limit == null) {
      return httpResponseFactory.badRequest(Map.of("message", "Invalid limit"));
    }
    var ctx = requestContextFactory.createCtx(event);
    var continuation = value(queryParams, "continuation");
    return httpResponseFactory.ok(
        orderService.findOrders(ctx, new FindOrdersRequest(limit, continuation)));
  }

  @Nullable
  private static Integer parseLimit(@Nullable String value) {
    if (value == null) {
      return null;
    }
    try {
      return Integer.valueOf(value);
    } catch (NumberFormatException e) {
      return null;
    }
  }

  @Nullable
  private static String value(@Nullable Map<String, String> values, String key) {
    return values == null ? null : values.get(key);
  }
}
