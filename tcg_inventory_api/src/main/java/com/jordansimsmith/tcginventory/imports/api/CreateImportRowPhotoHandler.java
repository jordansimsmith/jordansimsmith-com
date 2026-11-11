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
import com.jordansimsmith.tcginventory.imports.client.CreateImportRowPhotoRequest;
import com.jordansimsmith.tcginventory.imports.client.ImportService;
import java.util.Base64;
import java.util.Map;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CreateImportRowPhotoHandler
    implements RequestHandler<APIGatewayV2HTTPEvent, APIGatewayV2HTTPResponse> {
  private static final Logger LOGGER = LoggerFactory.getLogger(CreateImportRowPhotoHandler.class);

  private final ImportService importService;
  private final RequestContextFactory requestContextFactory;
  private final HttpResponseFactory httpResponseFactory;

  public CreateImportRowPhotoHandler() {
    this(TcgInventoryFactory.create());
  }

  @VisibleForTesting
  public CreateImportRowPhotoHandler(TcgInventoryFactory factory) {
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
      LOGGER.error("error processing create import row photo request", e);
      throw new RuntimeException(e);
    }
  }

  private APIGatewayV2HTTPResponse doHandleRequest(APIGatewayV2HTTPEvent event)
      throws ServiceException {
    var headers = event.getHeaders();
    var contentType = headers == null ? null : headers.get("content-type");
    if (contentType == null || !contentType.startsWith("image/jpeg")) {
      return httpResponseFactory.badRequest(Map.of("message", "Content-Type must be image/jpeg"));
    }
    var body = event.getBody();
    if (body == null || body.isBlank()) {
      return httpResponseFactory.badRequest(Map.of("message", "JPEG body is required"));
    }
    byte[] photoBytes;
    try {
      photoBytes = Base64.getDecoder().decode(body);
    } catch (IllegalArgumentException e) {
      return httpResponseFactory.badRequest(Map.of("message", "JPEG body is required"));
    }

    var pathParameters = event.getPathParameters();
    var importId = pathParameters == null ? null : pathParameters.get("import_id");
    Integer position =
        parsePosition(pathParameters == null ? null : pathParameters.get("position"));
    if (position == null) {
      return httpResponseFactory.badRequest(Map.of("message", "invalid position"));
    }
    var ctx = requestContextFactory.createCtx(event);
    importService.createImportRowPhoto(
        ctx, new CreateImportRowPhotoRequest(importId, position, photoBytes));
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
