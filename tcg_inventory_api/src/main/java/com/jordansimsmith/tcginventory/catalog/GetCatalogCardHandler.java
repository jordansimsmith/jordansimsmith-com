package com.jordansimsmith.tcginventory.catalog;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.annotations.VisibleForTesting;
import com.jordansimsmith.http.HttpResponseFactory;
import com.jordansimsmith.http.RequestContextFactory;
import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.service.ServiceException;
import com.jordansimsmith.tcginventory.games.client.Game;
import com.jordansimsmith.tcginventory.games.client.GamesService;
import com.jordansimsmith.tcginventory.games.client.GetGameRequest;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GetCatalogCardHandler
    implements RequestHandler<APIGatewayV2HTTPEvent, APIGatewayV2HTTPResponse> {
  private static final Logger LOGGER = LoggerFactory.getLogger(GetCatalogCardHandler.class);

  private record ErrorResponse(@JsonProperty("message") String message) {}

  private final RequestContextFactory requestContextFactory;
  private final HttpResponseFactory httpResponseFactory;
  private final Catalogs catalogs;
  private final GamesService gamesService;

  public GetCatalogCardHandler() {
    this(CatalogFactory.create());
  }

  @VisibleForTesting
  GetCatalogCardHandler(CatalogFactory factory) {
    this.requestContextFactory = factory.requestContextFactory();
    this.httpResponseFactory = factory.httpResponseFactory();
    this.catalogs = factory.catalogs();
    this.gamesService = factory.gamesService();
  }

  @Override
  public APIGatewayV2HTTPResponse handleRequest(APIGatewayV2HTTPEvent event, Context context) {
    try {
      var ctx = requestContextFactory.createCtx(event);
      var game = requiredGame(event, ctx);
      Map<String, String> pathParameters = event.getPathParameters();
      var externalId = pathParameters != null ? pathParameters.get("external_id") : null;
      if (externalId == null || externalId.isBlank()) {
        throw new CatalogException.BadRequest("external_id is required");
      }
      return httpResponseFactory.ok(catalogs.forGame(game).getCard(game, externalId));
    } catch (ServiceException e) {
      return httpResponseFactory.serviceError(e);
    } catch (CatalogException.BadRequest e) {
      return httpResponseFactory.badRequest(new ErrorResponse(e.getMessage()));
    } catch (CatalogException.NotFound e) {
      return httpResponseFactory.notFound(new ErrorResponse(e.getMessage()));
    } catch (CatalogException.Unavailable e) {
      LOGGER.warn("catalog provider unavailable", e);
      return httpResponseFactory.serviceUnavailable(
          new ErrorResponse("catalog is temporarily unavailable"));
    } catch (Exception e) {
      LOGGER.error("error processing catalog request", e);
      throw new RuntimeException(e);
    }
  }

  private Game requiredGame(APIGatewayV2HTTPEvent event, RequestContext ctx)
      throws ServiceException {
    var gameId = requiredQueryParameter(event, "game");
    return gamesService.getGame(ctx, new GetGameRequest(gameId)).game();
  }

  private String requiredQueryParameter(APIGatewayV2HTTPEvent event, String key) {
    var queryParameters = event.getQueryStringParameters();
    var value = queryParameters != null ? queryParameters.get(key) : null;
    if (value == null || value.isBlank()) {
      throw new CatalogException.BadRequest(key + " is required");
    }
    return value;
  }
}
