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
import com.jordansimsmith.tcginventory.imports.client.DeleteImportRequest;
import com.jordansimsmith.tcginventory.imports.client.ImportService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DeleteImportHandler
    implements RequestHandler<APIGatewayV2HTTPEvent, APIGatewayV2HTTPResponse> {
  private static final Logger LOGGER = LoggerFactory.getLogger(DeleteImportHandler.class);

  private final ImportService importService;
  private final RequestContextFactory requestContextFactory;
  private final HttpResponseFactory httpResponseFactory;

  public DeleteImportHandler() {
    this(TcgInventoryFactory.create());
  }

  @VisibleForTesting
  public DeleteImportHandler(TcgInventoryFactory factory) {
    importService = factory.importService();
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
      LOGGER.error("error processing delete import request", e);
      throw new RuntimeException(e);
    }
  }

  private APIGatewayV2HTTPResponse doHandleRequest(APIGatewayV2HTTPEvent event)
      throws ServiceException {
    var ctx = requestContextFactory.createCtx(event);
    var pathParameters = event.getPathParameters();
    var importId = pathParameters == null ? null : pathParameters.get("import_id");
    importService.deleteImport(ctx, new DeleteImportRequest(importId));
    return httpResponseFactory.noContent();
  }
}
