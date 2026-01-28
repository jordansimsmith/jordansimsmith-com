package com.jordansimsmith.immersiontracker;

import static org.assertj.core.api.Assertions.assertThat;

import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jordansimsmith.dynamodb.DynamoDbContainer;
import com.jordansimsmith.dynamodb.DynamoDbUtils;
import com.jordansimsmith.time.FakeClock;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryEnhancedRequest;

@Testcontainers
public class SyncSpotifyHandlerIntegrationTest {
  private FakeClock clock;
  private ObjectMapper objectMapper;
  private DynamoDbTable<ImmersionTrackerItem> immersionTrackerTable;
  private FakeSpotifyClient fakeSpotifyClient;

  private SyncSpotifyHandler syncSpotifyHandler;

  @Container private static final DynamoDbContainer dynamoDbContainer = new DynamoDbContainer();

  @BeforeAll
  static void setUpBeforeClass() {
    var factory = ImmersionTrackerTestFactory.create(dynamoDbContainer.getEndpoint());
    var table = factory.immersionTrackerTable();
    DynamoDbUtils.createTable(factory.dynamoDbClient(), table);
  }

  @BeforeEach
  void setUp() {
    var factory = ImmersionTrackerTestFactory.create(dynamoDbContainer.getEndpoint());

    clock = factory.fakeClock();
    objectMapper = factory.objectMapper();
    immersionTrackerTable = factory.immersionTrackerTable();
    fakeSpotifyClient = factory.fakeSpotifyClient();

    DynamoDbUtils.reset(factory.dynamoDbClient());

    syncSpotifyHandler = new SyncSpotifyHandler(factory);
  }

  @Test
  void handleRequestShouldSyncSpotifyEpisodes() throws Exception {
    // arrange
    clock.setTime(Instant.ofEpochMilli(123_000));
    var user = "alice";

    fakeSpotifyClient.setEpisode(
        "4qjerzMw8jfD30VOG0tjpK",
        "No 1 紹介(しょうかい) Introduction",
        "6Nl8RDfPxsk4h4bfWe76Kg",
        "The Miku Real Japanese Podcast",
        Duration.ofMinutes(6).plusSeconds(28));
    fakeSpotifyClient.setEpisode(
        "anotherEpisodeId",
        "Test Episode",
        "testShowId",
        "Test Show",
        Duration.ofMinutes(30).plusSeconds(15));

    var episodeIds = List.of("4qjerzMw8jfD30VOG0tjpK", "anotherEpisodeId");
    var body =
        objectMapper.writeValueAsString(new SyncSpotifyHandler.SyncSpotifyRequest(episodeIds));

    // act
    var authHeader =
        "Basic "
            + Base64.getEncoder()
                .encodeToString((user + ":password").getBytes(StandardCharsets.UTF_8));
    var req =
        APIGatewayV2HTTPEvent.builder()
            .withHeaders(Map.of("Authorization", authHeader))
            .withBody(body)
            .build();
    var res = syncSpotifyHandler.handleRequest(req, null);

    // assert
    assertThat(res.getStatusCode()).isEqualTo(200);
    assertThat(res.getHeaders()).containsEntry("Content-Type", "application/json; charset=utf-8");

    var episodesAdded =
        objectMapper.readValue(res.getBody(), SyncSpotifyHandler.SyncSpotifyResponse.class);
    assertThat(episodesAdded.episodesAdded()).isEqualTo(2);

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

    assertThat(items).hasSize(4);

    var episode1 =
        ImmersionTrackerItem.createSpotifyEpisode(
            user,
            "6Nl8RDfPxsk4h4bfWe76Kg",
            "4qjerzMw8jfD30VOG0tjpK",
            "No 1 紹介(しょうかい) Introduction",
            Duration.ofMinutes(6).plusSeconds(28),
            clock.now());
    assertThat(items).contains(episode1);

    var episode2 =
        ImmersionTrackerItem.createSpotifyEpisode(
            user,
            "testShowId",
            "anotherEpisodeId",
            "Test Episode",
            Duration.ofMinutes(30).plusSeconds(15),
            clock.now());
    assertThat(items).contains(episode2);

    var show1 =
        ImmersionTrackerItem.createSpotifyShow(
            user, "6Nl8RDfPxsk4h4bfWe76Kg", "The Miku Real Japanese Podcast");
    assertThat(items).contains(show1);

    var show2 = ImmersionTrackerItem.createSpotifyShow(user, "testShowId", "Test Show");
    assertThat(items).contains(show2);
  }

  @Test
  void handleRequestShouldSkipDuplicateEpisodes() throws Exception {
    // arrange
    clock.setTime(Instant.ofEpochMilli(123_000));
    var user = "alice";

    fakeSpotifyClient.setEpisode(
        "4qjerzMw8jfD30VOG0tjpK",
        "No 1 紹介(しょうかい) Introduction",
        "6Nl8RDfPxsk4h4bfWe76Kg",
        "The Miku Real Japanese Podcast",
        Duration.ofMinutes(6).plusSeconds(28));

    // Pre-populate with existing episode
    var existingEpisode =
        ImmersionTrackerItem.createSpotifyEpisode(
            user,
            "6Nl8RDfPxsk4h4bfWe76Kg",
            "4qjerzMw8jfD30VOG0tjpK",
            "Existing Title",
            Duration.ofMinutes(6).plusSeconds(30),
            Instant.EPOCH);
    immersionTrackerTable.putItem(existingEpisode);

    var episodeIds = List.of("4qjerzMw8jfD30VOG0tjpK");
    var body =
        objectMapper.writeValueAsString(new SyncSpotifyHandler.SyncSpotifyRequest(episodeIds));

    // act
    var authHeader =
        "Basic "
            + Base64.getEncoder()
                .encodeToString((user + ":password").getBytes(StandardCharsets.UTF_8));
    var req =
        APIGatewayV2HTTPEvent.builder()
            .withHeaders(Map.of("Authorization", authHeader))
            .withBody(body)
            .build();
    var res = syncSpotifyHandler.handleRequest(req, null);

    // assert
    assertThat(res.getStatusCode()).isEqualTo(200);

    var episodesAdded =
        objectMapper.readValue(res.getBody(), SyncSpotifyHandler.SyncSpotifyResponse.class);
    assertThat(episodesAdded.episodesAdded()).isEqualTo(0);

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

    // Should still have only the original episode
    assertThat(items).hasSize(1);
    assertThat(items.get(0)).isEqualTo(existingEpisode);
  }

  @Test
  void handleRequestShouldNotDuplicateShows() throws Exception {
    // arrange
    clock.setTime(Instant.ofEpochMilli(123_000));
    var user = "alice";

    fakeSpotifyClient.setEpisode(
        "episode1", "Episode 1", "testShowId", "Test Show", Duration.ofMinutes(30).plusSeconds(15));
    fakeSpotifyClient.setEpisode(
        "episode2", "Episode 2", "testShowId", "Test Show", Duration.ofMinutes(25).plusSeconds(20));

    var episodeIds = List.of("episode1", "episode2");
    var body =
        objectMapper.writeValueAsString(new SyncSpotifyHandler.SyncSpotifyRequest(episodeIds));

    // act
    var authHeader =
        "Basic "
            + Base64.getEncoder()
                .encodeToString((user + ":password").getBytes(StandardCharsets.UTF_8));
    var req =
        APIGatewayV2HTTPEvent.builder()
            .withHeaders(Map.of("Authorization", authHeader))
            .withBody(body)
            .build();
    var res = syncSpotifyHandler.handleRequest(req, null);

    // assert
    assertThat(res.getStatusCode()).isEqualTo(200);

    var episodesAdded =
        objectMapper.readValue(res.getBody(), SyncSpotifyHandler.SyncSpotifyResponse.class);
    assertThat(episodesAdded.episodesAdded()).isEqualTo(2);

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

    assertThat(items).hasSize(3);

    var episode1 =
        ImmersionTrackerItem.createSpotifyEpisode(
            user,
            "testShowId",
            "episode1",
            "Episode 1",
            Duration.ofMinutes(30).plusSeconds(15),
            clock.now());
    assertThat(items).contains(episode1);

    var episode2 =
        ImmersionTrackerItem.createSpotifyEpisode(
            user,
            "testShowId",
            "episode2",
            "Episode 2",
            Duration.ofMinutes(25).plusSeconds(20),
            clock.now());
    assertThat(items).contains(episode2);

    var show = ImmersionTrackerItem.createSpotifyShow(user, "testShowId", "Test Show");
    assertThat(items).contains(show);
  }
}
