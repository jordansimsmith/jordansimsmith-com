package com.jordansimsmith.immersiontracker;

import static org.assertj.core.api.Assertions.assertThat;

import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jordansimsmith.dynamodb.DynamoDbContainer;
import com.jordansimsmith.dynamodb.DynamoDbUtils;
import com.jordansimsmith.time.FakeClock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryEnhancedRequest;

@Testcontainers
public class SyncYoutubeHandlerIntegrationTest {
  private FakeClock clock;
  private ObjectMapper objectMapper;
  private DynamoDbTable<ImmersionTrackerItem> immersionTrackerTable;
  private FakeYoutubeClient fakeYoutubeClient;

  private SyncYoutubeHandler syncYoutubeHandler;

  @Container DynamoDbContainer dynamoDbContainer = new DynamoDbContainer();

  @BeforeEach
  void setUp() {
    var factory = ImmersionTrackerTestFactory.create(dynamoDbContainer.getEndpoint());

    clock = factory.fakeClock();
    objectMapper = factory.objectMapper();
    immersionTrackerTable = factory.immersionTrackerTable();
    fakeYoutubeClient = factory.fakeYoutubeClient();

    DynamoDbUtils.createTable(factory.dynamoDbClient(), immersionTrackerTable);

    syncYoutubeHandler = new SyncYoutubeHandler(factory);
  }

  @Test
  void handleRequestShouldSyncYoutubeVideos() throws Exception {
    // arrange
    clock.setTime(Instant.ofEpochMilli(123_000));
    var user = "alice";

    fakeYoutubeClient.setVideo(
        "dQw4w9WgXcQ",
        "Never Gonna Give You Up",
        "UCuAXFkgsw1L7xaCfnd5JJOw",
        Duration.ofMinutes(3).plusSeconds(32));
    fakeYoutubeClient.setVideo(
        "jNhQd1gUEFo", "Test Video", "UCTestChannel", Duration.ofMinutes(5).plusSeconds(15));

    var videoIds = List.of("dQw4w9WgXcQ", "jNhQd1gUEFo");
    var body = objectMapper.writeValueAsString(new SyncYoutubeHandler.SyncYoutubeRequest(videoIds));

    // act
    var req =
        APIGatewayV2HTTPEvent.builder()
            .withQueryStringParameters(Map.of("user", user))
            .withBody(body)
            .build();
    var res = syncYoutubeHandler.handleRequest(req, null);

    // assert
    assertThat(res.getStatusCode()).isEqualTo(200);
    assertThat(res.getHeaders()).containsEntry("Content-Type", "application/json; charset=utf-8");

    var videosAdded =
        objectMapper.readValue(res.getBody(), SyncYoutubeHandler.SyncYoutubeResponse.class);
    assertThat(videosAdded.videosAdded()).isEqualTo(2);

    var items =
        immersionTrackerTable
            .query(
                QueryEnhancedRequest.builder()
                    .queryConditional(
                        QueryConditional.keyEqualTo(
                            Key.builder()
                                .partitionValue(ImmersionTrackerItem.formatPk(user))
                                .build()))
                    .build())
            .items()
            .stream()
            .toList();

    assertThat(items).hasSize(2);

    var video1 =
        ImmersionTrackerItem.createYoutubeVideo(
            user,
            "UCuAXFkgsw1L7xaCfnd5JJOw",
            "dQw4w9WgXcQ",
            "Never Gonna Give You Up",
            Duration.ofMinutes(3).plusSeconds(32),
            clock.now());
    assertThat(items).contains(video1);

    var video2 =
        ImmersionTrackerItem.createYoutubeVideo(
            user,
            "UCTestChannel",
            "jNhQd1gUEFo",
            "Test Video",
            Duration.ofMinutes(5).plusSeconds(15),
            clock.now());
    assertThat(items).contains(video2);
  }

  @Test
  void handleRequestShouldSkipDuplicateVideos() throws Exception {
    // arrange
    clock.setTime(Instant.ofEpochMilli(123_000));
    var user = "alice";

    fakeYoutubeClient.setVideo(
        "dQw4w9WgXcQ",
        "Never Gonna Give You Up",
        "UCuAXFkgsw1L7xaCfnd5JJOw",
        Duration.ofMinutes(3).plusSeconds(32));

    // Pre-populate with existing video
    var existingVideo =
        ImmersionTrackerItem.createYoutubeVideo(
            user,
            "UCuAXFkgsw1L7xaCfnd5JJOw",
            "dQw4w9WgXcQ",
            "Existing Title",
            Duration.ofMinutes(3).plusSeconds(30),
            Instant.EPOCH);
    immersionTrackerTable.putItem(existingVideo);

    var videoIds = List.of("dQw4w9WgXcQ");
    var body = objectMapper.writeValueAsString(new SyncYoutubeHandler.SyncYoutubeRequest(videoIds));

    // act
    var req =
        APIGatewayV2HTTPEvent.builder()
            .withQueryStringParameters(Map.of("user", user))
            .withBody(body)
            .build();
    var res = syncYoutubeHandler.handleRequest(req, null);

    // assert
    assertThat(res.getStatusCode()).isEqualTo(200);

    var videosAdded =
        objectMapper.readValue(res.getBody(), SyncYoutubeHandler.SyncYoutubeResponse.class);
    assertThat(videosAdded.videosAdded()).isEqualTo(0);

    var items =
        immersionTrackerTable
            .query(
                QueryEnhancedRequest.builder()
                    .queryConditional(
                        QueryConditional.keyEqualTo(
                            Key.builder()
                                .partitionValue(ImmersionTrackerItem.formatPk(user))
                                .build()))
                    .build())
            .items()
            .stream()
            .toList();

    // Should still have only the original video
    assertThat(items).hasSize(1);
    assertThat(items.get(0)).isEqualTo(existingVideo);
  }
}
