package com.jordansimsmith.immersiontracker;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.jordansimsmith.time.Clock;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;

public class SyncSpotifyHandler
    implements RequestHandler<APIGatewayV2HTTPEvent, APIGatewayV2HTTPResponse> {
  private static final Logger LOGGER = LoggerFactory.getLogger(SyncSpotifyHandler.class);
  @VisibleForTesting static final ZoneId ZONE_ID = ZoneId.of("Pacific/Auckland");

  private final Clock clock;
  private final ObjectMapper objectMapper;
  private final DynamoDbTable<ImmersionTrackerItem> immersionTrackerTable;
  private final SpotifyClient spotifyClient;

  @VisibleForTesting
  record SyncSpotifyRequest(@JsonProperty("episode_ids") List<String> episodeIds) {}

  @VisibleForTesting
  record SyncSpotifyResponse(@JsonProperty("episodes_added") int episodesAdded) {}

  public SyncSpotifyHandler() {
    this(ImmersionTrackerFactory.create());
  }

  @VisibleForTesting
  SyncSpotifyHandler(ImmersionTrackerFactory factory) {
    this.clock = factory.clock();
    this.objectMapper = factory.objectMapper();
    this.immersionTrackerTable = factory.immersionTrackerTable();
    this.spotifyClient = factory.spotifyClient();
  }

  @Override
  public APIGatewayV2HTTPResponse handleRequest(APIGatewayV2HTTPEvent event, Context context) {
    try {
      return doHandleRequest(event, context);
    } catch (Exception e) {
      LOGGER.error("Error processing Spotify sync request", e);
      throw new RuntimeException(e);
    }
  }

  private APIGatewayV2HTTPResponse doHandleRequest(APIGatewayV2HTTPEvent event, Context context)
      throws Exception {
    var user = event.getQueryStringParameters().get("user");
    Preconditions.checkNotNull(user);

    var body = objectMapper.readValue(event.getBody(), SyncSpotifyRequest.class);

    var now = clock.now().atZone(ZONE_ID).toInstant();
    var episodesAdded = 0;

    for (var episodeId : body.episodeIds) {
      var existingEpisode =
          immersionTrackerTable.getItem(
              Key.builder()
                  .partitionValue(ImmersionTrackerItem.formatPk(user))
                  .sortValue(ImmersionTrackerItem.formatSpotifyEpisodeSk(episodeId))
                  .build());

      if (existingEpisode != null) {
        continue;
      }

      var episode = spotifyClient.getEpisode(episodeId);
      var episodeItem =
          ImmersionTrackerItem.createSpotifyEpisode(
              user, episode.showId(), episode.id(), episode.title(), episode.duration(), now);
      immersionTrackerTable.putItem(episodeItem);
      episodesAdded++;

      var existingShow =
          immersionTrackerTable.getItem(
              Key.builder()
                  .partitionValue(ImmersionTrackerItem.formatPk(user))
                  .sortValue(ImmersionTrackerItem.formatSpotifyShowSk(episode.showId()))
                  .build());

      if (existingShow == null) {
        var showItem =
            ImmersionTrackerItem.createSpotifyShow(user, episode.showId(), episode.showName());
        immersionTrackerTable.putItem(showItem);
      }
    }

    var res = new SyncSpotifyResponse(episodesAdded);

    return APIGatewayV2HTTPResponse.builder()
        .withStatusCode(200)
        .withHeaders(Map.of("Content-Type", "application/json; charset=utf-8"))
        .withBody(objectMapper.writeValueAsString(res))
        .build();
  }
}
