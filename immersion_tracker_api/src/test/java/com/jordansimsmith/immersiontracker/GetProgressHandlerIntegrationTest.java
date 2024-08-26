package com.jordansimsmith.immersiontracker;

import static org.assertj.core.api.Assertions.assertThat;

import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jordansimsmith.testcontainers.DynamoDbContainer;
import com.jordansimsmith.time.FakeClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.services.dynamodb.waiters.DynamoDbWaiter;

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

    var dynamoDbClient = factory.dynamoDbClient();
    immersionTrackerTable = factory.immersionTrackerTable();
    immersionTrackerTable.createTable();
    try (var waiter = DynamoDbWaiter.builder().client(dynamoDbClient).build()) {
      var res =
          waiter
              .waitUntilTableExists(b -> b.tableName(immersionTrackerTable.tableName()).build())
              .matched();
      res.response().orElseThrow();
    }

    getProgressHandler = new GetProgressHandler(factory);
  }

  @Test
  void handleRequestShouldCalculateProgress() throws Exception {
    // arrange
    var now = (int) fakeClock.now().atZone(GetProgressHandler.ZONE_ID).toInstant().getEpochSecond();
    var episode1 =
        ImmersionTrackerItem.createEpisode("jordansimsmith", "show1", "episode1", now - 100);
    var episode2 =
        ImmersionTrackerItem.createEpisode("jordansimsmith", "show1", "episode2", now + 100);
    var episode3 =
        ImmersionTrackerItem.createEpisode("jordansimsmith", "show2", "episode1", now - 100);
    var show = ImmersionTrackerItem.createShow("jordansimsmith", "show1");
    show.setTvdbId(1);
    show.setTvdbName("my show");

    immersionTrackerTable.putItem(episode1);
    immersionTrackerTable.putItem(episode2);
    immersionTrackerTable.putItem(episode3);
    immersionTrackerTable.putItem(show);

    // act
    var res = getProgressHandler.handleRequest(APIGatewayV2HTTPEvent.builder().build(), null);

    // assert
    assertThat(res.getStatusCode()).isEqualTo(200);
    assertThat(res.getHeaders()).containsEntry("Content-Type", "application/json; charset=utf-8");

    var progress = objectMapper.readValue(res.getBody(), GetProgressHandler.ProgressResponse.class);
    assertThat(progress).isNotNull();
    assertThat(progress.totalEpisodesWatched()).isEqualTo(3);
    assertThat(progress.totalHoursWatched()).isEqualTo(1);
    assertThat(progress.episodesWatchedToday()).isEqualTo(1);

    var shows = progress.shows();
    assertThat(shows).hasSize(2);
    assertThat(shows.get(0).name()).isEqualTo("my show");
    assertThat(shows.get(0).episodesWatched()).isEqualTo(2);
    assertThat(shows.get(1).name()).isNull();
    assertThat(shows.get(1).episodesWatched()).isEqualTo(1);
  }
}
