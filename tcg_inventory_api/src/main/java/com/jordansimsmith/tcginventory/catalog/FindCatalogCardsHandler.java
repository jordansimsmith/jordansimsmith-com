package com.jordansimsmith.tcginventory.catalog;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.annotations.VisibleForTesting;
import com.jordansimsmith.http.HttpResponseFactory;
import com.jordansimsmith.http.RequestContextFactory;
import com.jordansimsmith.tcginventory.games.Games;
import com.jordansimsmith.tcginventory.games.Games.Game;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class FindCatalogCardsHandler
    implements RequestHandler<APIGatewayV2HTTPEvent, APIGatewayV2HTTPResponse> {
  private static final Logger LOGGER = LoggerFactory.getLogger(FindCatalogCardsHandler.class);

  private record ErrorResponse(@JsonProperty("message") String message) {}

  private final RequestContextFactory requestContextFactory;
  private final HttpResponseFactory httpResponseFactory;
  private final Catalogs catalogs;

  public FindCatalogCardsHandler() {
    this(CatalogFactory.create());
  }

  @VisibleForTesting
  FindCatalogCardsHandler(CatalogFactory factory) {
    this.requestContextFactory = factory.requestContextFactory();
    this.httpResponseFactory = factory.httpResponseFactory();
    this.catalogs = factory.catalogs();
  }

  @Override
  public APIGatewayV2HTTPResponse handleRequest(APIGatewayV2HTTPEvent event, Context context) {
    try {
      requestContextFactory.createCtx(event);
      var game = requiredGame(event);
      var finish = requiredQueryParameter(event, "finish");
      if (!game.supportsFinish(finish)) {
        throw new CatalogException.BadRequest("finish is unsupported for game " + game.id());
      }
      var query = requiredQueryParameter(event, "query");
      var queryParameters = event.getQueryStringParameters();
      var continuation = queryParameters != null ? queryParameters.get("continuation") : null;
      var page = catalogs.forGame(game).search(query, finish, continuation);
      return httpResponseFactory.ok(page);
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

  private Game requiredGame(APIGatewayV2HTTPEvent event) {
    var gameId = requiredQueryParameter(event, "game");
    try {
      return Games.get(gameId);
    } catch (IllegalArgumentException e) {
      throw new CatalogException.BadRequest(e.getMessage());
    }
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
