package com.jordansimsmith.tcginventory.imports.api;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.annotations.VisibleForTesting;
import com.jordansimsmith.http.HttpResponseFactory;
import com.jordansimsmith.http.RequestContextFactory;
import com.jordansimsmith.service.ServiceException;
import com.jordansimsmith.tcginventory.TcgInventoryFactory;
import com.jordansimsmith.tcginventory.imports.client.ImportService;
import com.jordansimsmith.tcginventory.imports.client.UpdateImportRowRequest;
import com.jordansimsmith.tcginventory.inventory.client.Condition;
import java.util.Map;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class UpdateImportRowHandler
    implements RequestHandler<APIGatewayV2HTTPEvent, APIGatewayV2HTTPResponse> {
  private static final Logger LOGGER = LoggerFactory.getLogger(UpdateImportRowHandler.class);

  private final ImportService importService;
  private final RequestContextFactory requestContextFactory;
  private final HttpResponseFactory httpResponseFactory;
  private final ObjectMapper objectMapper;

  public UpdateImportRowHandler() {
    this(TcgInventoryFactory.create());
  }

  @VisibleForTesting
  public UpdateImportRowHandler(TcgInventoryFactory factory) {
    importService = factory.importService();
    requestContextFactory = factory.requestContextFactory();
    httpResponseFactory = factory.httpResponseFactory();
    objectMapper = factory.objectMapper();
  }

  @Override
  public APIGatewayV2HTTPResponse handleRequest(APIGatewayV2HTTPEvent event, Context context) {
    try {
      return doHandleRequest(event);
    } catch (ServiceException e) {
      return httpResponseFactory.serviceError(e);
    } catch (Exception e) {
      LOGGER.error("error processing update import row request", e);
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
    if (event.getBody() == null) {
      return httpResponseFactory.badRequest(Map.of("message", "invalid request body"));
    }
    ConditionRequest body;
    try {
      body = objectMapper.readValue(event.getBody(), ConditionRequest.class);
    } catch (JsonProcessingException e) {
      return httpResponseFactory.badRequest(Map.of("message", "invalid request body"));
    }
    if (body == null) {
      return httpResponseFactory.badRequest(Map.of("message", "invalid request body"));
    }
    if (body.condition() == null) {
      return httpResponseFactory.badRequest(Map.of("message", "invalid condition"));
    }
    Condition condition;
    try {
      condition = Condition.valueOf(body.condition());
    } catch (IllegalArgumentException e) {
      return httpResponseFactory.badRequest(Map.of("message", "invalid condition"));
    }
    var ctx = requestContextFactory.createCtx(event);
    var importId = pathParameters == null ? null : pathParameters.get("import_id");
    importService.updateImportRow(ctx, new UpdateImportRowRequest(importId, position, condition));
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

  private record ConditionRequest(@JsonProperty("condition") @Nullable String condition) {}
}
