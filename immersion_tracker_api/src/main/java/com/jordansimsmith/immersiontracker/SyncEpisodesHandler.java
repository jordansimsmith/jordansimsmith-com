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
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;

public class SyncEpisodesHandler
    implements RequestHandler<APIGatewayV2HTTPEvent, APIGatewayV2HTTPResponse> {
  @VisibleForTesting static final ZoneId ZONE_ID = ZoneId.of("Pacific/Auckland");

  private final Clock clock;
  private final ObjectMapper objectMapper;
  private final DynamoDbTable<ImmersionTrackerItem> immersionTrackerTable;

  @VisibleForTesting
  record SyncEpisodesRequest(@JsonProperty("episodes") List<Episode> episodes) {}

  @VisibleForTesting
  record Episode(
      @JsonProperty("folder_name") String folderName, @JsonProperty("file_name") String fileName) {}

  @VisibleForTesting
  record SyncEpisodesResponse(@JsonProperty("episodes_added") int episodesAdded) {}

  public SyncEpisodesHandler() {
    this(ImmersionTrackerFactory.create());
  }

  @VisibleForTesting
  SyncEpisodesHandler(ImmersionTrackerFactory factory) {
    this.clock = factory.clock();
    this.objectMapper = factory.objectMapper();
    this.immersionTrackerTable = factory.immersionTrackerTable();
  }

  @Override
  public APIGatewayV2HTTPResponse handleRequest(APIGatewayV2HTTPEvent event, Context context) {
    try {
      return doHandleRequest(event, context);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  private APIGatewayV2HTTPResponse doHandleRequest(APIGatewayV2HTTPEvent event, Context context)
      throws Exception {
    var user = event.getQueryStringParameters().get("user");
    Preconditions.checkNotNull(user);

    var body = objectMapper.readValue(event.getBody(), SyncEpisodesRequest.class);

    var now = clock.now().atZone(ZONE_ID).toInstant();
    var episodesAdded = 0;

    for (var e : body.episodes) {
      var show =
          immersionTrackerTable.getItem(
              Key.builder()
                  .partitionValue(ImmersionTrackerItem.formatPk(user))
                  .sortValue(ImmersionTrackerItem.formatShowSk(e.folderName))
                  .build());
      if (show == null) {
        show = ImmersionTrackerItem.createShow(user, e.folderName);
        immersionTrackerTable.putItem(show);
      }

      var episode =
          immersionTrackerTable.getItem(
              Key.builder()
                  .partitionValue(ImmersionTrackerItem.formatPk(user))
                  .sortValue(ImmersionTrackerItem.formatEpisodeSk(e.folderName, e.fileName))
                  .build());
      if (episode == null) {
        episode =
            ImmersionTrackerItem.createEpisode(
                user, e.folderName, e.fileName, now.getEpochSecond());
        immersionTrackerTable.putItem(episode);
        episodesAdded++;
      }
    }

    var res = new SyncEpisodesResponse(episodesAdded);

    return APIGatewayV2HTTPResponse.builder()
        .withStatusCode(200)
        .withHeaders(Map.of("Content-Type", "application/json; charset=utf-8"))
        .withBody(objectMapper.writeValueAsString(res))
        .build();
  }
}
