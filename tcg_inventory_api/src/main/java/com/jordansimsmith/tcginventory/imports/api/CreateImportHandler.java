package com.jordansimsmith.tcginventory.imports.api;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import com.google.common.annotations.VisibleForTesting;
import com.jordansimsmith.http.HttpResponseFactory;
import com.jordansimsmith.http.RequestContextFactory;
import com.jordansimsmith.service.ServiceException;
import com.jordansimsmith.tcginventory.TcgInventoryFactory;
import com.jordansimsmith.tcginventory.imports.client.CreateImportRequest;
import com.jordansimsmith.tcginventory.imports.client.ImportService;
import com.jordansimsmith.ulid.UlidGenerator;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CreateImportHandler
    implements RequestHandler<APIGatewayV2HTTPEvent, APIGatewayV2HTTPResponse> {
  private static final Logger LOGGER = LoggerFactory.getLogger(CreateImportHandler.class);

  private final ImportService importService;
  private final RequestContextFactory requestContextFactory;
  private final HttpResponseFactory httpResponseFactory;
  private final UlidGenerator ulidGenerator;

  public CreateImportHandler() {
    this(TcgInventoryFactory.create());
  }

  @VisibleForTesting
  public CreateImportHandler(TcgInventoryFactory factory) {
    importService = factory.importService();
    requestContextFactory = factory.requestContextFactory();
    httpResponseFactory = factory.httpResponseFactory();
    ulidGenerator = factory.ulidGenerator();
  }

  @Override
  public APIGatewayV2HTTPResponse handleRequest(APIGatewayV2HTTPEvent event, Context context) {
    try {
      return doHandleRequest(event);
    } catch (ServiceException e) {
      return httpResponseFactory.serviceError(e);
    } catch (Exception e) {
      LOGGER.error("error processing create import request", e);
      throw new RuntimeException(e);
    }
  }

  private APIGatewayV2HTTPResponse doHandleRequest(APIGatewayV2HTTPEvent event)
      throws ServiceException {
    var queryParams = event.getQueryStringParameters();
    var game = queryParams == null ? null : queryParams.get("game");
    if (game == null || game.isBlank()) {
      return httpResponseFactory.badRequest(Map.of("message", "game query parameter is required"));
    }
    var headers = event.getHeaders();
    var contentType = headers == null ? null : headers.get("content-type");
    if (contentType == null || !contentType.startsWith("text/csv")) {
      return httpResponseFactory.badRequest(Map.of("message", "Content-Type must be text/csv"));
    }
    var csv = event.getBody();
    if (csv == null) {
      return httpResponseFactory.badRequest(Map.of("message", "CSV body is required"));
    }

    var ctx = requestContextFactory.createCtx(event);
    var response =
        importService.createImport(
            ctx,
            new CreateImportRequest(
                ulidGenerator.generate(), game, csv, queryParams.get("filename"), null));
    return httpResponseFactory.ok(response);
  }
}
