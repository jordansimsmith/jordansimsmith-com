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
import java.util.HashSet;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryEnhancedRequest;

public class SyncSpotifyHandler
    implements RequestHandler<APIGatewayV2HTTPEvent, APIGatewayV2HTTPResponse> {
  private static final Logger LOGGER = LoggerFactory.getLogger(SyncSpotifyHandler.class);
  @VisibleForTesting static final ZoneId ZONE_ID = ZoneId.of("Pacific/Auckland");

  private final Clock clock;
  private final ObjectMapper objectMapper;
  private final RequestContextFactory requestContextFactory;
  private final HttpResponseFactory httpResponseFactory;
  private final DynamoDbTable<ImmersionTrackerItem> immersionTrackerTable;
  private final SpotifyClient spotifyClient;

  @VisibleForTesting
  record SyncSpotifyRequest(
      @JsonProperty("episode_ids") List<String> episodeIds,
      @JsonProperty("backfill") Boolean backfill) {}

  @VisibleForTesting
  record SyncSpotifyResponse(@JsonProperty("episodes_added") int episodesAdded) {}

  public SyncSpotifyHandler() {
    this(ImmersionTrackerFactory.create());
  }

  @VisibleForTesting
  SyncSpotifyHandler(ImmersionTrackerFactory factory) {
    this.clock = factory.clock();
    this.objectMapper = factory.objectMapper();
    this.requestContextFactory = factory.requestContextFactory();
    this.httpResponseFactory = factory.httpResponseFactory();
    this.immersionTrackerTable = factory.immersionTrackerTable();
    this.spotifyClient = factory.spotifyClient();
  }

  @Override
  public APIGatewayV2HTTPResponse handleRequest(APIGatewayV2HTTPEvent event, Context context) {
    try {
      return doHandleRequest(event);
    } catch (Exception e) {
      LOGGER.error("Error processing Spotify sync request", e);
      throw new RuntimeException(e);
    }
  }

  private APIGatewayV2HTTPResponse doHandleRequest(APIGatewayV2HTTPEvent event) throws Exception {
    var user = requestContextFactory.createCtx(event).user();

    var body = objectMapper.readValue(event.getBody(), SyncSpotifyRequest.class);
    var backfill = Boolean.TRUE.equals(body.backfill());

    var now = clock.now().atZone(ZONE_ID).toInstant();

    var existingEpisodeIds = new HashSet<String>();
    var existingShowIds = new HashSet<String>();
    var existingItems =
        immersionTrackerTable
            .query(
                QueryEnhancedRequest.builder()
                    .queryConditional(
                        QueryConditional.sortBeginsWith(
                            Key.builder()
                                .partitionValue(ImmersionTrackerItem.formatPk(user))
                                .sortValue("SPOTIFY")
                                .build()))
                    .build())
            .items();
    for (var item : existingItems) {
      var sk = item.getSk();
      if (sk.startsWith(ImmersionTrackerItem.SPOTIFYEPISODE_PREFIX)) {
        existingEpisodeIds.add(item.getSpotifyEpisodeId());
      } else if (sk.startsWith(ImmersionTrackerItem.SPOTIFYSHOW_PREFIX)) {
        existingShowIds.add(item.getSpotifyShowId());
      }
    }

    var episodesAdded = 0;

    for (var episodeId : body.episodeIds) {
      if (!backfill && existingEpisodeIds.contains(episodeId)) {
        continue;
      }

      var target = spotifyClient.getEpisode(episodeId);

      if (existingShowIds.add(target.showId())) {
        immersionTrackerTable.putItem(
            ImmersionTrackerItem.createSpotifyShow(
                user, target.showId(), target.showName(), target.showArtworkUrl()));
      }

      if (existingEpisodeIds.add(target.id())) {
        immersionTrackerTable.putItem(
            ImmersionTrackerItem.createSpotifyEpisode(
                user, target.showId(), target.id(), target.title(), target.duration(), now));
        episodesAdded++;
      }

      if (!backfill) {
        continue;
      }

      var siblings = spotifyClient.findShowEpisodes(target.showId());
      for (var sibling : siblings) {
        if (sibling.releaseDate().isAfter(target.releaseDate())) {
          continue;
        }

        if (existingEpisodeIds.add(sibling.id())) {
          immersionTrackerTable.putItem(
              ImmersionTrackerItem.createSpotifyEpisode(
                  user, target.showId(), sibling.id(), sibling.title(), sibling.duration(), now));
          episodesAdded++;
        }
      }
    }

    var res = new SyncSpotifyResponse(episodesAdded);

    return httpResponseFactory.ok(res);
  }
}
