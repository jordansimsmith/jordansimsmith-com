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
import java.time.LocalDate;
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

    fakeSpotifyClient.setEpisodeDetails(
        "4qjerzMw8jfD30VOG0tjpK",
        "No 1 紹介(しょうかい) Introduction",
        "6Nl8RDfPxsk4h4bfWe76Kg",
        "The Miku Real Japanese Podcast",
        "https://i.scdn.co/image/miku-podcast-cover",
        Duration.ofMinutes(6).plusSeconds(28),
        LocalDate.of(2021, 3, 28));
    fakeSpotifyClient.setEpisodeDetails(
        "anotherEpisodeId",
        "Test Episode",
        "testShowId",
        "Test Show",
        "https://i.scdn.co/image/test-show-cover",
        Duration.ofMinutes(30).plusSeconds(15),
        LocalDate.of(2022, 1, 1));

    var body = sendRequest(user, List.of("4qjerzMw8jfD30VOG0tjpK", "anotherEpisodeId"), false);

    // act
    var res = syncSpotifyHandler.handleRequest(body, null);

    // assert
    assertThat(res.getStatusCode()).isEqualTo(200);
    assertThat(res.getHeaders()).containsEntry("Content-Type", "application/json; charset=utf-8");

    var episodesAdded =
        objectMapper.readValue(res.getBody(), SyncSpotifyHandler.SyncSpotifyResponse.class);
    assertThat(episodesAdded.episodesAdded()).isEqualTo(2);

    var items = queryUserItems(user);
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
            user,
            "6Nl8RDfPxsk4h4bfWe76Kg",
            "The Miku Real Japanese Podcast",
            "https://i.scdn.co/image/miku-podcast-cover");
    assertThat(items).contains(show1);

    var show2 =
        ImmersionTrackerItem.createSpotifyShow(
            user, "testShowId", "Test Show", "https://i.scdn.co/image/test-show-cover");
    assertThat(items).contains(show2);
  }

  @Test
  void handleRequestShouldSkipDuplicateEpisodes() throws Exception {
    // arrange
    clock.setTime(Instant.ofEpochMilli(123_000));
    var user = "alice";

    fakeSpotifyClient.setEpisodeDetails(
        "4qjerzMw8jfD30VOG0tjpK",
        "No 1 紹介(しょうかい) Introduction",
        "6Nl8RDfPxsk4h4bfWe76Kg",
        "The Miku Real Japanese Podcast",
        "https://i.scdn.co/image/miku-podcast-cover",
        Duration.ofMinutes(6).plusSeconds(28),
        LocalDate.of(2021, 3, 28));

    var existingEpisode =
        ImmersionTrackerItem.createSpotifyEpisode(
            user,
            "6Nl8RDfPxsk4h4bfWe76Kg",
            "4qjerzMw8jfD30VOG0tjpK",
            "Existing Title",
            Duration.ofMinutes(6).plusSeconds(30),
            Instant.EPOCH);
    immersionTrackerTable.putItem(existingEpisode);

    var body = sendRequest(user, List.of("4qjerzMw8jfD30VOG0tjpK"), false);

    // act
    var res = syncSpotifyHandler.handleRequest(body, null);

    // assert
    assertThat(res.getStatusCode()).isEqualTo(200);

    var episodesAdded =
        objectMapper.readValue(res.getBody(), SyncSpotifyHandler.SyncSpotifyResponse.class);
    assertThat(episodesAdded.episodesAdded()).isEqualTo(0);

    var items = queryUserItems(user);
    assertThat(items).hasSize(1);
    assertThat(items.get(0)).isEqualTo(existingEpisode);
  }

  @Test
  void handleRequestShouldNotDuplicateShows() throws Exception {
    // arrange
    clock.setTime(Instant.ofEpochMilli(123_000));
    var user = "alice";

    fakeSpotifyClient.setEpisodeDetails(
        "episode1",
        "Episode 1",
        "testShowId",
        "Test Show",
        "https://i.scdn.co/image/test-show-cover",
        Duration.ofMinutes(30).plusSeconds(15),
        LocalDate.of(2021, 3, 28));
    fakeSpotifyClient.setEpisodeDetails(
        "episode2",
        "Episode 2",
        "testShowId",
        "Test Show",
        "https://i.scdn.co/image/test-show-cover",
        Duration.ofMinutes(25).plusSeconds(20),
        LocalDate.of(2021, 4, 4));

    var body = sendRequest(user, List.of("episode1", "episode2"), false);

    // act
    var res = syncSpotifyHandler.handleRequest(body, null);

    // assert
    assertThat(res.getStatusCode()).isEqualTo(200);

    var episodesAdded =
        objectMapper.readValue(res.getBody(), SyncSpotifyHandler.SyncSpotifyResponse.class);
    assertThat(episodesAdded.episodesAdded()).isEqualTo(2);

    var items = queryUserItems(user);
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

    var show =
        ImmersionTrackerItem.createSpotifyShow(
            user, "testShowId", "Test Show", "https://i.scdn.co/image/test-show-cover");
    assertThat(items).contains(show);
  }

  @Test
  void handleRequestShouldBackfillEpisodesOnOrBeforeTargetReleaseDate() throws Exception {
    // arrange
    clock.setTime(Instant.ofEpochMilli(123_000));
    var user = "alice";

    fakeSpotifyClient.setEpisodeDetails(
        "targetEpisodeId",
        "Episode 3",
        "showId",
        "Show Name",
        "https://i.scdn.co/image/show-cover",
        Duration.ofMinutes(10),
        LocalDate.of(2021, 4, 4));
    fakeSpotifyClient.setShowEpisodes(
        "showId",
        List.of(
            new SpotifyClient.Episode(
                "futureEpisodeId", "Episode 4", Duration.ofMinutes(11), LocalDate.of(2021, 4, 11)),
            new SpotifyClient.Episode(
                "targetEpisodeId", "Episode 3", Duration.ofMinutes(10), LocalDate.of(2021, 4, 4)),
            new SpotifyClient.Episode(
                "siblingEpisode2", "Episode 2", Duration.ofMinutes(9), LocalDate.of(2021, 3, 28)),
            new SpotifyClient.Episode(
                "siblingEpisode1", "Episode 1", Duration.ofMinutes(8), LocalDate.of(2021, 3, 21))));

    var body = sendRequest(user, List.of("targetEpisodeId"), true);

    // act
    var res = syncSpotifyHandler.handleRequest(body, null);

    // assert
    assertThat(res.getStatusCode()).isEqualTo(200);

    var episodesAdded =
        objectMapper.readValue(res.getBody(), SyncSpotifyHandler.SyncSpotifyResponse.class);
    assertThat(episodesAdded.episodesAdded()).isEqualTo(3);

    var items = queryUserItems(user);
    assertThat(items).hasSize(4);

    var target =
        ImmersionTrackerItem.createSpotifyEpisode(
            user, "showId", "targetEpisodeId", "Episode 3", Duration.ofMinutes(10), clock.now());
    var sibling2 =
        ImmersionTrackerItem.createSpotifyEpisode(
            user, "showId", "siblingEpisode2", "Episode 2", Duration.ofMinutes(9), clock.now());
    var sibling1 =
        ImmersionTrackerItem.createSpotifyEpisode(
            user, "showId", "siblingEpisode1", "Episode 1", Duration.ofMinutes(8), clock.now());
    var show =
        ImmersionTrackerItem.createSpotifyShow(
            user, "showId", "Show Name", "https://i.scdn.co/image/show-cover");

    assertThat(items).contains(target, sibling1, sibling2, show);
    assertThat(items.stream().map(ImmersionTrackerItem::getSpotifyEpisodeId))
        .doesNotContain("futureEpisodeId");
  }

  @Test
  void handleRequestShouldSkipBackfilledSiblingsAlreadyPresent() throws Exception {
    // arrange
    clock.setTime(Instant.ofEpochMilli(123_000));
    var user = "alice";

    fakeSpotifyClient.setEpisodeDetails(
        "targetEpisodeId",
        "Episode 2",
        "showId",
        "Show Name",
        "https://i.scdn.co/image/show-cover",
        Duration.ofMinutes(10),
        LocalDate.of(2021, 3, 28));
    fakeSpotifyClient.setShowEpisodes(
        "showId",
        List.of(
            new SpotifyClient.Episode(
                "targetEpisodeId", "Episode 2", Duration.ofMinutes(10), LocalDate.of(2021, 3, 28)),
            new SpotifyClient.Episode(
                "existingSibling", "Episode 1", Duration.ofMinutes(9), LocalDate.of(2021, 3, 21))));

    var existingSibling =
        ImmersionTrackerItem.createSpotifyEpisode(
            user,
            "showId",
            "existingSibling",
            "Episode 1 (older)",
            Duration.ofMinutes(8),
            Instant.EPOCH);
    immersionTrackerTable.putItem(existingSibling);

    var body = sendRequest(user, List.of("targetEpisodeId"), true);

    // act
    var res = syncSpotifyHandler.handleRequest(body, null);

    // assert
    var episodesAdded =
        objectMapper.readValue(res.getBody(), SyncSpotifyHandler.SyncSpotifyResponse.class);
    assertThat(episodesAdded.episodesAdded()).isEqualTo(1);

    var items = queryUserItems(user);
    assertThat(items).hasSize(3);

    var target =
        ImmersionTrackerItem.createSpotifyEpisode(
            user, "showId", "targetEpisodeId", "Episode 2", Duration.ofMinutes(10), clock.now());
    var show =
        ImmersionTrackerItem.createSpotifyShow(
            user, "showId", "Show Name", "https://i.scdn.co/image/show-cover");

    assertThat(items).contains(target, show, existingSibling);
  }

  @Test
  void handleRequestShouldBackfillAcrossMultipleTargets() throws Exception {
    // arrange
    clock.setTime(Instant.ofEpochMilli(123_000));
    var user = "alice";

    fakeSpotifyClient.setEpisodeDetails(
        "showAEp2",
        "A2",
        "showA",
        "Show A",
        "https://i.scdn.co/image/show-a-cover",
        Duration.ofMinutes(10),
        LocalDate.of(2021, 1, 8));
    fakeSpotifyClient.setEpisodeDetails(
        "showBEp1",
        "B1",
        "showB",
        "Show B",
        "https://i.scdn.co/image/show-b-cover",
        Duration.ofMinutes(15),
        LocalDate.of(2022, 5, 1));
    fakeSpotifyClient.setShowEpisodes(
        "showA",
        List.of(
            new SpotifyClient.Episode(
                "showAEp2", "A2", Duration.ofMinutes(10), LocalDate.of(2021, 1, 8)),
            new SpotifyClient.Episode(
                "showAEp1", "A1", Duration.ofMinutes(9), LocalDate.of(2021, 1, 1))));
    fakeSpotifyClient.setShowEpisodes(
        "showB",
        List.of(
            new SpotifyClient.Episode(
                "showBEp1", "B1", Duration.ofMinutes(15), LocalDate.of(2022, 5, 1))));

    var body = sendRequest(user, List.of("showAEp2", "showBEp1"), true);

    // act
    var res = syncSpotifyHandler.handleRequest(body, null);

    // assert
    var episodesAdded =
        objectMapper.readValue(res.getBody(), SyncSpotifyHandler.SyncSpotifyResponse.class);
    assertThat(episodesAdded.episodesAdded()).isEqualTo(3);

    var items = queryUserItems(user);
    assertThat(items).hasSize(5);
    assertThat(items.stream().map(ImmersionTrackerItem::getSpotifyShowId).filter(s -> s != null))
        .contains("showA", "showB");
  }

  @Test
  void handleRequestShouldHandleMixedReleaseDatePrecisionWhenBackfilling() throws Exception {
    // arrange
    clock.setTime(Instant.ofEpochMilli(123_000));
    var user = "alice";

    fakeSpotifyClient.setEpisodeDetails(
        "targetEpisodeId",
        "Latest",
        "showId",
        "Show Name",
        "https://i.scdn.co/image/show-cover",
        Duration.ofMinutes(10),
        LocalDate.of(2021, 6, 1));
    fakeSpotifyClient.setShowEpisodes(
        "showId",
        List.of(
            new SpotifyClient.Episode(
                "targetEpisodeId", "Latest", Duration.ofMinutes(10), LocalDate.of(2021, 6, 1)),
            new SpotifyClient.Episode(
                "monthEpisode", "Month", Duration.ofMinutes(9), LocalDate.of(2020, 6, 1)),
            new SpotifyClient.Episode(
                "yearEpisode", "Year", Duration.ofMinutes(8), LocalDate.of(2019, 1, 1))));

    var body = sendRequest(user, List.of("targetEpisodeId"), true);

    // act
    var res = syncSpotifyHandler.handleRequest(body, null);

    // assert
    var episodesAdded =
        objectMapper.readValue(res.getBody(), SyncSpotifyHandler.SyncSpotifyResponse.class);
    assertThat(episodesAdded.episodesAdded()).isEqualTo(3);

    var items = queryUserItems(user);
    assertThat(items.stream().map(ImmersionTrackerItem::getSpotifyEpisodeId).filter(s -> s != null))
        .contains("targetEpisodeId", "monthEpisode", "yearEpisode");
  }

  @Test
  void handleRequestShouldNotBackfillWhenFlagIsFalseOrAbsent() throws Exception {
    // arrange
    clock.setTime(Instant.ofEpochMilli(123_000));
    var user = "alice";

    fakeSpotifyClient.setEpisodeDetails(
        "targetEpisodeId",
        "Episode 2",
        "showId",
        "Show Name",
        "https://i.scdn.co/image/show-cover",
        Duration.ofMinutes(10),
        LocalDate.of(2021, 3, 28));
    fakeSpotifyClient.setShowEpisodes(
        "showId",
        List.of(
            new SpotifyClient.Episode(
                "targetEpisodeId", "Episode 2", Duration.ofMinutes(10), LocalDate.of(2021, 3, 28)),
            new SpotifyClient.Episode(
                "siblingEpisode", "Episode 1", Duration.ofMinutes(8), LocalDate.of(2021, 3, 21))));

    var body = sendRequest(user, List.of("targetEpisodeId"), false);

    // act
    var res = syncSpotifyHandler.handleRequest(body, null);

    // assert
    var episodesAdded =
        objectMapper.readValue(res.getBody(), SyncSpotifyHandler.SyncSpotifyResponse.class);
    assertThat(episodesAdded.episodesAdded()).isEqualTo(1);

    var items = queryUserItems(user);
    assertThat(items.stream().map(ImmersionTrackerItem::getSpotifyEpisodeId).filter(s -> s != null))
        .containsExactly("targetEpisodeId");
  }

  private APIGatewayV2HTTPEvent sendRequest(String user, List<String> episodeIds, boolean backfill)
      throws Exception {
    var body =
        objectMapper.writeValueAsString(
            new SyncSpotifyHandler.SyncSpotifyRequest(episodeIds, backfill));
    var authHeader =
        "Basic "
            + Base64.getEncoder()
                .encodeToString((user + ":password").getBytes(StandardCharsets.UTF_8));
    return APIGatewayV2HTTPEvent.builder()
        .withHeaders(Map.of("Authorization", authHeader))
        .withBody(body)
        .build();
  }

  private List<ImmersionTrackerItem> queryUserItems(String user) {
    return immersionTrackerTable
        .query(
            QueryEnhancedRequest.builder()
                .queryConditional(
                    QueryConditional.keyEqualTo(
                        Key.builder().partitionValue(ImmersionTrackerItem.formatPk(user)).build()))
                .build())
        .items()
        .stream()
        .toList();
  }
}
