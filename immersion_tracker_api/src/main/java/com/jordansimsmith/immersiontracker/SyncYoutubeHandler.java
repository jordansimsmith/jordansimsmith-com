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

public class SyncYoutubeHandler
    implements RequestHandler<APIGatewayV2HTTPEvent, APIGatewayV2HTTPResponse> {
  private static final Logger LOGGER = LoggerFactory.getLogger(SyncYoutubeHandler.class);
  @VisibleForTesting static final ZoneId ZONE_ID = ZoneId.of("Pacific/Auckland");

  private final Clock clock;
  private final ObjectMapper objectMapper;
  private final RequestContextFactory requestContextFactory;
  private final HttpResponseFactory httpResponseFactory;
  private final DynamoDbTable<ImmersionTrackerItem> immersionTrackerTable;
  private final YoutubeClient youtubeClient;

  @VisibleForTesting
  record SyncYoutubeRequest(@JsonProperty("video_ids") List<String> videoIds) {}

  @VisibleForTesting
  record SyncYoutubeResponse(@JsonProperty("videos_added") int videosAdded) {}

  public SyncYoutubeHandler() {
    this(ImmersionTrackerFactory.create());
  }

  @VisibleForTesting
  SyncYoutubeHandler(ImmersionTrackerFactory factory) {
    this.clock = factory.clock();
    this.objectMapper = factory.objectMapper();
    this.requestContextFactory = factory.requestContextFactory();
    this.httpResponseFactory = factory.httpResponseFactory();
    this.immersionTrackerTable = factory.immersionTrackerTable();
    this.youtubeClient = factory.youtubeClient();
  }

  @Override
  public APIGatewayV2HTTPResponse handleRequest(APIGatewayV2HTTPEvent event, Context context) {
    try {
      return doHandleRequest(event, context);
    } catch (Exception e) {
      LOGGER.error("Error processing YouTube sync request", e);
      throw new RuntimeException(e);
    }
  }

  private APIGatewayV2HTTPResponse doHandleRequest(APIGatewayV2HTTPEvent event, Context context)
      throws Exception {
    var user = requestContextFactory.createCtx(event).user();

    var body = objectMapper.readValue(event.getBody(), SyncYoutubeRequest.class);

    var now = clock.now().atZone(ZONE_ID).toInstant();
    var videosAdded = 0;

    for (var videoId : body.videoIds) {
      var existingVideo =
          immersionTrackerTable.getItem(
              Key.builder()
                  .partitionValue(ImmersionTrackerItem.formatPk(user))
                  .sortValue(ImmersionTrackerItem.formatYoutubeVideoSk(videoId))
                  .build());

      if (existingVideo != null) {
        continue;
      }

      var video = youtubeClient.getVideo(videoId);
      var videoItem =
          ImmersionTrackerItem.createYoutubeVideo(
              user, video.channelId(), video.id(), video.title(), video.duration(), now);
      immersionTrackerTable.putItem(videoItem);
      videosAdded++;

      var existingChannel =
          immersionTrackerTable.getItem(
              Key.builder()
                  .partitionValue(ImmersionTrackerItem.formatPk(user))
                  .sortValue(ImmersionTrackerItem.formatYoutubeChannelSk(video.channelId()))
                  .build());

      if (existingChannel == null) {
        var channelItem =
            ImmersionTrackerItem.createYoutubeChannel(
                user, video.channelId(), video.channelTitle());
        immersionTrackerTable.putItem(channelItem);
      }
    }

    var res = new SyncYoutubeResponse(videosAdded);

    return httpResponseFactory.ok(res);
  }
}
