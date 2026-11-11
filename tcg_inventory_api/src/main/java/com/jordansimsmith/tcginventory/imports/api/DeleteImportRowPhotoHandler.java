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
import com.jordansimsmith.tcginventory.imports.client.DeleteImportRowPhotoRequest;
import com.jordansimsmith.tcginventory.imports.client.ImportService;
import java.util.Map;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DeleteImportRowPhotoHandler
    implements RequestHandler<APIGatewayV2HTTPEvent, APIGatewayV2HTTPResponse> {
  private static final Logger LOGGER = LoggerFactory.getLogger(DeleteImportRowPhotoHandler.class);

  private final ImportService importService;
  private final RequestContextFactory requestContextFactory;
  private final HttpResponseFactory httpResponseFactory;

  public DeleteImportRowPhotoHandler() {
    this(TcgInventoryFactory.create());
  }

  @VisibleForTesting
  public DeleteImportRowPhotoHandler(TcgInventoryFactory factory) {
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
      LOGGER.error("error processing delete import row photo request", e);
      throw new RuntimeException(e);
    }
  }

  private APIGatewayV2HTTPResponse doHandleRequest(APIGatewayV2HTTPEvent event)
      throws ServiceException {
    var pathParameters = event.getPathParameters();
    Integer position =
        parsePosition(pathParameters == null ? null : pathParameters.get("position"));
    if (position == null) {
      return httpResponseFactory.badRequest(Map.of("message", "invalid position"));
    }
    var importId = pathParameters == null ? null : pathParameters.get("import_id");
    var photoId = pathParameters == null ? null : pathParameters.get("photo_id");
    var ctx = requestContextFactory.createCtx(event);
    importService.deleteImportRowPhoto(
        ctx, new DeleteImportRowPhotoRequest(importId, position, photoId));
    return httpResponseFactory.noContent();
  }

  @Nullable
  private static Integer parsePosition(@Nullable String value) {
    if (value == null) {
      return null;
    }
    try {
      return Integer.valueOf(value);
    } catch (NumberFormatException e) {
      return null;
    }
  }
}
