package com.jordansimsmith.tcginventory.catalog.api;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import com.google.common.annotations.VisibleForTesting;
import com.jordansimsmith.http.HttpResponseFactory;
import com.jordansimsmith.http.RequestContextFactory;
import com.jordansimsmith.service.BadRequestException;
import com.jordansimsmith.service.ServiceException;
import com.jordansimsmith.tcginventory.TcgInventoryFactory;
import com.jordansimsmith.tcginventory.catalog.client.CatalogService;
import com.jordansimsmith.tcginventory.catalog.client.SearchCardsRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SearchCardsHandler
    implements RequestHandler<APIGatewayV2HTTPEvent, APIGatewayV2HTTPResponse> {
  private static final Logger LOGGER = LoggerFactory.getLogger(SearchCardsHandler.class);

  private final CatalogService catalogService;
  private final RequestContextFactory requestContextFactory;
  private final HttpResponseFactory httpResponseFactory;

  public SearchCardsHandler() {
    this(TcgInventoryFactory.create());
  }

  @VisibleForTesting
  SearchCardsHandler(TcgInventoryFactory factory) {
    catalogService = factory.catalogService();
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
      LOGGER.error("error processing search cards request", e);
      throw new RuntimeException(e);
    }
  }

  private APIGatewayV2HTTPResponse doHandleRequest(APIGatewayV2HTTPEvent event)
      throws ServiceException {
    var queryParameters = event.getQueryStringParameters();
    var game = queryParameters == null ? null : queryParameters.get("game");
    var query = queryParameters == null ? null : queryParameters.get("query");
    var finish = queryParameters == null ? null : queryParameters.get("finish");
    var limitParam = queryParameters == null ? null : queryParameters.get("limit");
    Integer limit = null;
    if (limitParam != null) {
      try {
        limit = Integer.valueOf(limitParam);
      } catch (NumberFormatException e) {
        throw new BadRequestException("limit must be an integer");
      }
    }
    var continuation = queryParameters == null ? null : queryParameters.get("continuation");
    var ctx = requestContextFactory.createCtx(event);
    return httpResponseFactory.ok(
        catalogService.searchCards(
            ctx, new SearchCardsRequest(game, query, finish, limit, continuation)));
  }
}
