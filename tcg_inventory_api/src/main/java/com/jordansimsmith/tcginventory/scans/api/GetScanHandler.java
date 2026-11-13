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
import com.jordansimsmith.tcginventory.scans.client.GetScanRequest;
import com.jordansimsmith.tcginventory.scans.client.ScanService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GetScanHandler
    implements RequestHandler<APIGatewayV2HTTPEvent, APIGatewayV2HTTPResponse> {
  private static final Logger LOGGER = LoggerFactory.getLogger(GetScanHandler.class);

  private final ScanService scanService;
  private final RequestContextFactory requestContextFactory;
  private final HttpResponseFactory httpResponseFactory;

  public GetScanHandler() {
    this(TcgInventoryFactory.create());
  }

  @VisibleForTesting
  public GetScanHandler(TcgInventoryFactory factory) {
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
      LOGGER.error("error processing get scan request", e);
      throw new RuntimeException(e);
    }
  }

  private APIGatewayV2HTTPResponse doHandleRequest(APIGatewayV2HTTPEvent event)
      throws ServiceException {
    var pathParameters = event.getPathParameters();
    var scanId = pathParameters == null ? null : pathParameters.get("scan_id");
    var ctx = requestContextFactory.createCtx(event);
    var response = scanService.getScan(ctx, new GetScanRequest(scanId));
    return httpResponseFactory.ok(response);
  }
}
