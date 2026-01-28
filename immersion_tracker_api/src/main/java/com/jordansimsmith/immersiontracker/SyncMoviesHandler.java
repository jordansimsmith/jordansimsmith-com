package com.jordansimsmith.immersiontracker;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.annotations.VisibleForTesting;
import com.jordansimsmith.http.HttpResponseFactory;
import com.jordansimsmith.http.RequestContextFactory;
import com.jordansimsmith.time.Clock;
import java.time.ZoneId;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;

public class SyncMoviesHandler
    implements RequestHandler<APIGatewayV2HTTPEvent, APIGatewayV2HTTPResponse> {
  private static final Logger LOGGER = LoggerFactory.getLogger(SyncMoviesHandler.class);
  @VisibleForTesting static final ZoneId ZONE_ID = ZoneId.of("Pacific/Auckland");

  private final Clock clock;
  private final ObjectMapper objectMapper;
  private final RequestContextFactory requestContextFactory;
  private final HttpResponseFactory httpResponseFactory;
  private final DynamoDbTable<ImmersionTrackerItem> immersionTrackerTable;
  private final TvdbClient tvdbClient;

  @VisibleForTesting
  record Movie(@JsonProperty("file_name") String fileName, @JsonProperty("tvdb_id") int tvdbId) {}

  @VisibleForTesting
  record SyncMoviesRequest(@JsonProperty("movies") List<Movie> movies) {}

  @VisibleForTesting
  record SyncMoviesResponse(@JsonProperty("movies_added") int moviesAdded) {}

  public SyncMoviesHandler() {
    this(ImmersionTrackerFactory.create());
  }

  @VisibleForTesting
  SyncMoviesHandler(ImmersionTrackerFactory factory) {
    this.clock = factory.clock();
    this.objectMapper = factory.objectMapper();
    this.requestContextFactory = factory.requestContextFactory();
    this.httpResponseFactory = factory.httpResponseFactory();
    this.immersionTrackerTable = factory.immersionTrackerTable();
    this.tvdbClient = factory.tvdbClient();
  }

  @Override
  public APIGatewayV2HTTPResponse handleRequest(APIGatewayV2HTTPEvent event, Context context) {
    try {
      return doHandleRequest(event, context);
    } catch (Exception e) {
      LOGGER.error("Error processing movies sync request", e);
      throw new RuntimeException(e);
    }
  }

  private APIGatewayV2HTTPResponse doHandleRequest(APIGatewayV2HTTPEvent event, Context context)
      throws Exception {
    var user = requestContextFactory.createCtx(event).user();

    var body = objectMapper.readValue(event.getBody(), SyncMoviesRequest.class);

    var now = clock.now().atZone(ZONE_ID).toInstant();
    var moviesAdded = 0;

    for (var movie : body.movies) {
      var existingMovie =
          immersionTrackerTable.getItem(
              Key.builder()
                  .partitionValue(ImmersionTrackerItem.formatPk(user))
                  .sortValue(ImmersionTrackerItem.formatMovieSk(movie.fileName()))
                  .build());

      if (existingMovie != null) {
        continue;
      }

      var tvdbMovie = tvdbClient.getMovie(movie.tvdbId());
      var movieItem =
          ImmersionTrackerItem.createMovie(
              user,
              movie.fileName(),
              tvdbMovie.id(),
              tvdbMovie.name(),
              tvdbMovie.image(),
              tvdbMovie.duration(),
              now);
      immersionTrackerTable.putItem(movieItem);
      moviesAdded++;
    }

    var res = new SyncMoviesResponse(moviesAdded);

    return httpResponseFactory.ok(res);
  }
}
