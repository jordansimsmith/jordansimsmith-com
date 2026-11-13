package com.jordansimsmith.tcginventory.scans.api;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.annotations.VisibleForTesting;
import com.jordansimsmith.http.HttpResponseFactory;
import com.jordansimsmith.http.RequestContextFactory;
import com.jordansimsmith.service.BadRequestException;
import com.jordansimsmith.service.ServiceException;
import com.jordansimsmith.tcginventory.TcgInventoryFactory;
import com.jordansimsmith.tcginventory.scans.client.DeleteScanRowRequest;
import com.jordansimsmith.tcginventory.scans.client.ScanService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DeleteScanRowHandler
    implements RequestHandler<APIGatewayV2HTTPEvent, APIGatewayV2HTTPResponse> {
  private static final Logger LOGGER = LoggerFactory.getLogger(DeleteScanRowHandler.class);

  record ErrorResponse(@JsonProperty("message") String message) {}

  private final RequestContextFactory requestContextFactory;
  private final HttpResponseFactory httpResponseFactory;
  private final ScanService scanService;

  public DeleteScanRowHandler() {
    this(TcgInventoryFactory.create());
  }

  @VisibleForTesting
  public DeleteScanRowHandler(TcgInventoryFactory factory) {
    requestContextFactory = factory.requestContextFactory();
    httpResponseFactory = factory.httpResponseFactory();
    scanService = factory.scanService();
  }

  @Override
  public APIGatewayV2HTTPResponse handleRequest(APIGatewayV2HTTPEvent event, Context context) {
    try {
      return doHandleRequest(event);
    } catch (ServiceException e) {
      return httpResponseFactory.serviceError(e);
    } catch (Exception e) {
      LOGGER.error("error processing delete scan row request", e);
      throw new RuntimeException(e);
    }
  }

  private APIGatewayV2HTTPResponse doHandleRequest(APIGatewayV2HTTPEvent event)
      throws ServiceException {
    var pathParameters = event.getPathParameters();
    var scanId = pathParameters == null ? null : pathParameters.get("scan_id");
    var scanPositionValue = pathParameters == null ? null : pathParameters.get("scan_position");
    int scanPosition;
    try {
      scanPosition = Integer.parseInt(scanPositionValue);
    } catch (RuntimeException e) {
      throw new BadRequestException("invalid scan row path parameters", e);
    }
    scanService.deleteScanRow(
        requestContextFactory.createCtx(event), new DeleteScanRowRequest(scanId, scanPosition));
    return httpResponseFactory.noContent();
  }
}
