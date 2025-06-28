package com.jordansimsmith.immersiontracker;

import static org.assertj.core.api.Assertions.assertThat;

import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jordansimsmith.dynamodb.DynamoDbContainer;
import com.jordansimsmith.dynamodb.DynamoDbUtils;
import com.jordansimsmith.time.FakeClock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;

@Testcontainers
public class GetProgressHandlerIntegrationTest {
  private FakeClock fakeClock;
  private ObjectMapper objectMapper;
  private DynamoDbTable<ImmersionTrackerItem> immersionTrackerTable;

  private GetProgressHandler getProgressHandler;

  @Container DynamoDbContainer dynamoDbContainer = new DynamoDbContainer();

  @BeforeEach
  void setUp() {
    var factory = ImmersionTrackerTestFactory.create(dynamoDbContainer.getEndpoint());

    fakeClock = factory.fakeClock();
    objectMapper = factory.objectMapper();
    immersionTrackerTable = factory.immersionTrackerTable();

    DynamoDbUtils.createTable(factory.dynamoDbClient(), immersionTrackerTable);

    getProgressHandler = new GetProgressHandler(factory);
  }

  @Test
  void handleRequestShouldCalculateProgress() throws Exception {
    // arrange
    var user = "alice";
    fakeClock.setTime(Instant.ofEpochMilli(100_000_000));
    var now = fakeClock.now().atZone(GetProgressHandler.ZONE_ID).toInstant();
    var episode1 = ImmersionTrackerItem.createEpisode(user, "show1", "episode1", Instant.EPOCH);
    var episode2 = ImmersionTrackerItem.createEpisode(user, "show2", "episode2", now);
    var episode3 = ImmersionTrackerItem.createEpisode(user, "show3", "episode1", now);
    var show1 = ImmersionTrackerItem.createShow(user, "show1");
    show1.setTvdbId(1);
    show1.setTvdbName("my show");
    var show2 = ImmersionTrackerItem.createShow(user, "show2");
    show2.setTvdbId(1);
    show2.setTvdbName("my show");
    var show3 = ImmersionTrackerItem.createShow(user, "show3");
    show3.setTvdbId(2);
    show3.setTvdbName("my other show");

    immersionTrackerTable.putItem(episode1);
    immersionTrackerTable.putItem(episode2);
    immersionTrackerTable.putItem(episode3);
    immersionTrackerTable.putItem(show1);
    immersionTrackerTable.putItem(show2);
    immersionTrackerTable.putItem(show3);

    // act
    var req =
        APIGatewayV2HTTPEvent.builder().withQueryStringParameters(Map.of("user", user)).build();
    var res = getProgressHandler.handleRequest(req, null);

    // assert
    assertThat(res.getStatusCode()).isEqualTo(200);
    assertThat(res.getHeaders()).containsEntry("Content-Type", "application/json; charset=utf-8");

    var progress =
        objectMapper.readValue(res.getBody(), GetProgressHandler.GetProgressResponse.class);
    assertThat(progress).isNotNull();
    assertThat(progress.totalEpisodesWatched()).isEqualTo(3);
    assertThat(progress.totalHoursWatched()).isEqualTo(1);
    assertThat(progress.episodesWatchedToday()).isEqualTo(2);
    assertThat(progress.daysSinceFirstEpisode()).isEqualTo(1);

    var shows = progress.shows();
    assertThat(shows).hasSize(2);
    assertThat(shows.get(0).name()).isEqualTo("my show");
    assertThat(shows.get(0).episodesWatched()).isEqualTo(2);
    assertThat(shows.get(1).name()).isEqualTo("my other show");
    assertThat(shows.get(1).episodesWatched()).isEqualTo(1);
  }

  @Test
  void handleRequestShouldReturnUnknownShow() throws Exception {
    // arrange
    var user = "alice";
    var now = fakeClock.now().atZone(GetProgressHandler.ZONE_ID).toInstant();
    var episode1 = ImmersionTrackerItem.createEpisode(user, "show1", "episode1", now);
    var episode2 = ImmersionTrackerItem.createEpisode(user, "show1", "episode2", now);
    var episode3 = ImmersionTrackerItem.createEpisode(user, "show3", "episode1", now);
    var show1 = ImmersionTrackerItem.createShow(user, "show1");
    show1.setTvdbId(1);
    show1.setTvdbName("my show");

    immersionTrackerTable.putItem(episode1);
    immersionTrackerTable.putItem(episode2);
    immersionTrackerTable.putItem(episode3);
    immersionTrackerTable.putItem(show1);

    // act
    var req =
        APIGatewayV2HTTPEvent.builder().withQueryStringParameters(Map.of("user", user)).build();
    var res = getProgressHandler.handleRequest(req, null);

    // assert
    assertThat(res.getStatusCode()).isEqualTo(200);
    assertThat(res.getHeaders()).containsEntry("Content-Type", "application/json; charset=utf-8");

    var progress =
        objectMapper.readValue(res.getBody(), GetProgressHandler.GetProgressResponse.class);
    assertThat(progress).isNotNull();
    var shows = progress.shows();
    assertThat(shows).hasSize(2);
    assertThat(shows.get(0).name()).isEqualTo("my show");
    assertThat(shows.get(0).episodesWatched()).isEqualTo(2);
    assertThat(shows.get(1).name()).isNull();
    assertThat(shows.get(1).episodesWatched()).isEqualTo(1);
  }

  @Test
  void handleRequestShouldReturnPositiveWeeklyTrendWhenRecentViewingAboveAverage()
      throws Exception {
    // arrange
    var user = "alice";
    fakeClock.setTime(Instant.EPOCH.plus(28, ChronoUnit.DAYS));
    var now = fakeClock.now();
    var sevenDaysAgo = now.minus(7, ChronoUnit.DAYS);

    // 4 episodes over 28 days = 1 episode per week average
    var episode1 = ImmersionTrackerItem.createEpisode(user, "show1", "episode1", Instant.EPOCH);
    var episode2 =
        ImmersionTrackerItem.createEpisode(
            user, "show1", "episode2", Instant.EPOCH.plus(14, ChronoUnit.DAYS));
    var episode3 =
        ImmersionTrackerItem.createEpisode(
            user, "show1", "episode3", sevenDaysAgo.plus(1, ChronoUnit.DAYS));
    var episode4 =
        ImmersionTrackerItem.createEpisode(
            user, "show1", "episode4", now.minus(1, ChronoUnit.DAYS));

    immersionTrackerTable.putItem(episode1);
    immersionTrackerTable.putItem(episode2);
    immersionTrackerTable.putItem(episode3);
    immersionTrackerTable.putItem(episode4);

    // act
    var req =
        APIGatewayV2HTTPEvent.builder().withQueryStringParameters(Map.of("user", user)).build();
    var res = getProgressHandler.handleRequest(req, null);

    // assert
    var progress =
        objectMapper.readValue(res.getBody(), GetProgressHandler.GetProgressResponse.class);
    // 2 episodes in last 7 days vs 1 per week average = 100% increase
    assertThat(progress.weeklyTrendPercentage()).isEqualTo(100.0);
  }

  @Test
  void handleRequestShouldReturnNegativeWeeklyTrendWhenRecentViewingBelowAverage()
      throws Exception {
    // arrange
    var user = "alice";
    fakeClock.setTime(Instant.EPOCH.plus(14, ChronoUnit.DAYS));

    // 4 episodes over 14 days = 2 episodes per week average, but 0 in last 7 days
    var episode1 = ImmersionTrackerItem.createEpisode(user, "show1", "episode1", Instant.EPOCH);
    var episode2 =
        ImmersionTrackerItem.createEpisode(
            user, "show1", "episode2", Instant.EPOCH.plus(1, ChronoUnit.DAYS));
    var episode3 =
        ImmersionTrackerItem.createEpisode(
            user, "show1", "episode3", Instant.EPOCH.plus(2, ChronoUnit.DAYS));
    var episode4 =
        ImmersionTrackerItem.createEpisode(
            user, "show1", "episode4", Instant.EPOCH.plus(3, ChronoUnit.DAYS));

    immersionTrackerTable.putItem(episode1);
    immersionTrackerTable.putItem(episode2);
    immersionTrackerTable.putItem(episode3);
    immersionTrackerTable.putItem(episode4);

    // act
    var req =
        APIGatewayV2HTTPEvent.builder().withQueryStringParameters(Map.of("user", user)).build();
    var res = getProgressHandler.handleRequest(req, null);

    // assert
    var progress =
        objectMapper.readValue(res.getBody(), GetProgressHandler.GetProgressResponse.class);
    // 0 episodes in last 7 days vs 2 per week average = -100% decrease
    assertThat(progress.weeklyTrendPercentage()).isEqualTo(-100.0);
  }
}
