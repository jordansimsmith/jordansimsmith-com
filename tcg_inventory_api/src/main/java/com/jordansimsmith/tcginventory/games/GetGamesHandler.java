package com.jordansimsmith.tcginventory.games;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.annotations.VisibleForTesting;
import com.jordansimsmith.http.HttpResponseFactory;
import com.jordansimsmith.http.RequestContextFactory;
import com.jordansimsmith.tcginventory.TcgInventoryFactory;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GetGamesHandler
    implements RequestHandler<APIGatewayV2HTTPEvent, APIGatewayV2HTTPResponse> {
  private static final Logger LOGGER = LoggerFactory.getLogger(GetGamesHandler.class);

  record FinishResponse(
      @JsonProperty("id") String id, @JsonProperty("display_name") String displayName) {}

  record ScanReviewImageRegionResponse(
      @JsonProperty("id") String id,
      @JsonProperty("display_name") String displayName,
      @JsonProperty("x") double x,
      @JsonProperty("y") double y,
      @JsonProperty("width") double width,
      @JsonProperty("height") double height) {}

  record GameResponse(
      @JsonProperty("id") String id,
      @JsonProperty("display_name") String displayName,
      @JsonProperty("scanning_enabled") boolean scanningEnabled,
      @JsonProperty("csv_import_enabled") boolean csvImportEnabled,
      @JsonProperty("finishes") List<FinishResponse> finishes,
      @JsonProperty("scan_review_image_regions")
          List<ScanReviewImageRegionResponse> scanReviewImageRegions) {}

  record GetGamesResponse(@JsonProperty("games") List<GameResponse> games) {}

  private final RequestContextFactory requestContextFactory;
  private final HttpResponseFactory httpResponseFactory;

  public GetGamesHandler() {
    this(TcgInventoryFactory.create());
  }

  @VisibleForTesting
  GetGamesHandler(TcgInventoryFactory factory) {
    this(factory.requestContextFactory(), factory.httpResponseFactory());
  }

  @VisibleForTesting
  GetGamesHandler(
      RequestContextFactory requestContextFactory, HttpResponseFactory httpResponseFactory) {
    this.requestContextFactory = requestContextFactory;
    this.httpResponseFactory = httpResponseFactory;
  }

  @Override
  public APIGatewayV2HTTPResponse handleRequest(APIGatewayV2HTTPEvent event, Context context) {
    try {
      return doHandleRequest(event);
    } catch (Exception e) {
      LOGGER.error("error processing get games request", e);
      throw new RuntimeException(e);
    }
  }

  private APIGatewayV2HTTPResponse doHandleRequest(APIGatewayV2HTTPEvent event) {
    requestContextFactory.createCtx(event);

    var games =
        Games.all().stream()
            .map(
                game ->
                    new GameResponse(
                        game.id(),
                        game.displayName(),
                        game.scanningEnabled(),
                        game.csvImportEnabled(),
                        game.finishes().stream()
                            .map(finish -> new FinishResponse(finish.id(), finish.displayName()))
                            .toList(),
                        game.scanReviewImageRegions().stream()
                            .map(
                                region ->
                                    new ScanReviewImageRegionResponse(
                                        region.id(),
                                        region.displayName(),
                                        region.x(),
                                        region.y(),
                                        region.width(),
                                        region.height()))
                            .toList()))
            .toList();
    return httpResponseFactory.ok(new GetGamesResponse(games));
  }
}
