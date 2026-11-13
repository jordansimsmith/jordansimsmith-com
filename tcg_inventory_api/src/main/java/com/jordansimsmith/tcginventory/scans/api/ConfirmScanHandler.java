package com.jordansimsmith.tcginventory.scans.api;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.annotations.VisibleForTesting;
import com.jordansimsmith.http.HttpResponseFactory;
import com.jordansimsmith.http.RequestContextFactory;
import com.jordansimsmith.service.BadRequestException;
import com.jordansimsmith.service.ServiceException;
import com.jordansimsmith.tcginventory.TcgInventoryFactory;
import com.jordansimsmith.tcginventory.scans.client.ConfirmScanRequest;
import com.jordansimsmith.tcginventory.scans.client.ScanService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ConfirmScanHandler
    implements RequestHandler<APIGatewayV2HTTPEvent, APIGatewayV2HTTPResponse> {
  private static final Logger LOGGER = LoggerFactory.getLogger(ConfirmScanHandler.class);

  private final ObjectMapper objectMapper;
  private final ScanService scanService;
  private final RequestContextFactory requestContextFactory;
  private final HttpResponseFactory httpResponseFactory;

  public ConfirmScanHandler() {
    this(TcgInventoryFactory.create());
  }

  @VisibleForTesting
  public ConfirmScanHandler(TcgInventoryFactory factory) {
    objectMapper = factory.objectMapper();
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
      LOGGER.error("error processing confirm scan request", e);
      throw new RuntimeException(e);
    }
  }

  private APIGatewayV2HTTPResponse doHandleRequest(APIGatewayV2HTTPEvent event)
      throws ServiceException {
    var pathParameters = event.getPathParameters();
    var scanId = pathParameters == null ? null : pathParameters.get("scan_id");
    if (event.getBody() == null) {
      throw new BadRequestException("invalid request body");
    }
    ConfirmScanRequest request;
    try {
      request = objectMapper.readValue(event.getBody(), ConfirmScanRequest.class);
    } catch (JsonProcessingException e) {
      throw new BadRequestException("invalid request body", e);
    }
    if (request == null) {
      throw new BadRequestException("invalid request body");
    }
    scanService.confirmScan(
        requestContextFactory.createCtx(event), new ConfirmScanRequest(scanId, request.rows()));
    return httpResponseFactory.accepted();
  }
}
