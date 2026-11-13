package com.jordansimsmith.tcginventory.scans.api;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import com.google.common.annotations.VisibleForTesting;
import com.jordansimsmith.http.HttpResponseFactory;
import com.jordansimsmith.http.RequestContextFactory;
import com.jordansimsmith.service.ServiceException;
import com.jordansimsmith.tcginventory.TcgInventoryFactory;
import com.jordansimsmith.tcginventory.scans.client.FindScansRequest;
import com.jordansimsmith.tcginventory.scans.client.ScanService;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class FindScansHandler
    implements RequestHandler<APIGatewayV2HTTPEvent, APIGatewayV2HTTPResponse> {
  private static final Logger LOGGER = LoggerFactory.getLogger(FindScansHandler.class);

  private final ScanService scanService;
  private final RequestContextFactory requestContextFactory;
  private final HttpResponseFactory httpResponseFactory;

  public FindScansHandler() {
    this(TcgInventoryFactory.create());
  }

  @VisibleForTesting
  public FindScansHandler(TcgInventoryFactory factory) {
    scanService = factory.scanService();
    requestContextFactory = factory.requestContextFactory();
    httpResponseFactory = factory.httpResponseFactory();
  }

  @Override
  public APIGatewayV2HTTPResponse handleRequest(APIGatewayV2HTTPEvent event, Context context) {
    try {
      return doHandleRequest(event);
    } catch (ServiceException e) {
      return httpResponseFactory.serviceError(e);
    } catch (Exception e) {
      LOGGER.error("error processing find scans request", e);
      throw new RuntimeException(e);
    }
  }

  private APIGatewayV2HTTPResponse doHandleRequest(APIGatewayV2HTTPEvent event)
      throws ServiceException {
    var queryParams = event.getQueryStringParameters();
    var continuation = value(queryParams, "continuation");
    var limitParam = value(queryParams, "limit");
    Integer limit = parseLimit(limitParam);
    if (limitParam != null && limit == null) {
      return httpResponseFactory.badRequest(Map.of("message", "limit must be positive"));
    }
    var ctx = requestContextFactory.createCtx(event);
    var response = scanService.findScans(ctx, new FindScansRequest(limit, continuation));
    return httpResponseFactory.ok(response);
  }

  private static Integer parseLimit(String value) {
    if (value == null) {
      return null;
    }
    try {
      return Integer.valueOf(value);
    } catch (NumberFormatException e) {
      return null;
    }
  }

  private static String value(Map<String, String> values, String key) {
    return values == null ? null : values.get(key);
  }
}
