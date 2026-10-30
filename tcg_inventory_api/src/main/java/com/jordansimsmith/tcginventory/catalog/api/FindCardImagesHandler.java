package com.jordansimsmith.tcginventory.catalog.api;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import com.google.common.annotations.VisibleForTesting;
import com.jordansimsmith.http.HttpResponseFactory;
import com.jordansimsmith.http.RequestContextFactory;
import com.jordansimsmith.service.ServiceException;
import com.jordansimsmith.tcginventory.TcgInventoryFactory;
import com.jordansimsmith.tcginventory.catalog.client.CatalogService;
import com.jordansimsmith.tcginventory.catalog.client.FindCardImagesRequest;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class FindCardImagesHandler
    implements RequestHandler<FindCardImagesHandler.HttpEvent, APIGatewayV2HTTPResponse> {
  private static final Logger LOGGER = LoggerFactory.getLogger(FindCardImagesHandler.class);

  public static class HttpEvent extends APIGatewayV2HTTPEvent {
    private Map<String, List<String>> multiValueQueryStringParameters;

    public Map<String, List<String>> getMultiValueQueryStringParameters() {
      return multiValueQueryStringParameters;
    }

    public void setMultiValueQueryStringParameters(
        Map<String, List<String>> multiValueQueryStringParameters) {
      this.multiValueQueryStringParameters = multiValueQueryStringParameters;
    }
  }

  private final CatalogService catalogService;
  private final RequestContextFactory requestContextFactory;
  private final HttpResponseFactory httpResponseFactory;

  public FindCardImagesHandler() {
    this(TcgInventoryFactory.create());
  }

  @VisibleForTesting
  FindCardImagesHandler(TcgInventoryFactory factory) {
    this.catalogService = factory.catalogService();
    this.requestContextFactory = factory.requestContextFactory();
    this.httpResponseFactory = factory.httpResponseFactory();
  }

  @Override
  public APIGatewayV2HTTPResponse handleRequest(HttpEvent event, Context context) {
    try {
      return doHandleRequest(event);
    } catch (ServiceException e) {
      return httpResponseFactory.serviceError(e);
    } catch (Exception e) {
      LOGGER.error("error processing find card images request", e);
      throw new RuntimeException(e);
    }
  }

  private APIGatewayV2HTTPResponse doHandleRequest(HttpEvent event) throws ServiceException {
    Map<String, String> queryParams = event.getQueryStringParameters();
    var game = queryParams != null ? queryParams.get("game") : null;
    Map<String, List<String>> multiValueQueryParams = event.getMultiValueQueryStringParameters();
    var externalIds =
        multiValueQueryParams != null ? multiValueQueryParams.get("external_ids") : null;
    var ctx = requestContextFactory.createCtx(event);
    return httpResponseFactory.ok(
        catalogService.findCardImages(ctx, new FindCardImagesRequest(game, externalIds)));
  }
}
