package com.jordansimsmith.immersiontracker;

import static org.assertj.core.api.Assertions.assertThat;

import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jordansimsmith.dynamodb.DynamoDbContainer;
import com.jordansimsmith.dynamodb.DynamoDbUtils;
import com.jordansimsmith.time.FakeClock;
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
public class SyncEpisodesHandlerIntegrationTest {
  private FakeClock clock;
  private ObjectMapper objectMapper;
  private DynamoDbTable<ImmersionTrackerItem> immersionTrackerTable;

  private SyncEpisodesHandler syncEpisodesHandler;

  @Container DynamoDbContainer dynamoDbContainer = new DynamoDbContainer();

  @BeforeEach
  void setUp() {
    var factory = ImmersionTrackerTestFactory.create(dynamoDbContainer.getEndpoint());

    clock = factory.fakeClock();
    objectMapper = factory.objectMapper();
    immersionTrackerTable = factory.immersionTrackerTable();

    DynamoDbUtils.createTable(factory.dynamoDbClient(), immersionTrackerTable);

    syncEpisodesHandler = new SyncEpisodesHandler(factory);
  }

  @Test
  void handleRequestShouldSyncEpisodes() throws Exception {
    // arrange
    clock.setTime(Instant.ofEpochMilli(123_000));
    var user = "alice";
    var episode1 = ImmersionTrackerItem.createEpisode(user, "show1", "episode1", Instant.EPOCH);
    var episode2 = ImmersionTrackerItem.createEpisode(user, "show2", "episode1", Instant.EPOCH);
    var show1 = ImmersionTrackerItem.createShow(user, "show1");
    immersionTrackerTable.putItem(episode1);
    immersionTrackerTable.putItem(episode2);
    immersionTrackerTable.putItem(show1);

    var episode3 = new SyncEpisodesHandler.Episode("show1", "episode1");
    var episode4 = new SyncEpisodesHandler.Episode("show1", "episode2");
    var episode5 = new SyncEpisodesHandler.Episode("show2", "episode2");
    var episode6 = new SyncEpisodesHandler.Episode("show2", "episode3");
    var episodes = List.of(episode3, episode4, episode5, episode6);
    var body =
        objectMapper.writeValueAsString(new SyncEpisodesHandler.SyncEpisodesRequest(episodes));

    // act
    var req =
        APIGatewayV2HTTPEvent.builder()
            .withQueryStringParameters(Map.of("user", user))
            .withBody(body)
            .build();
    var res = syncEpisodesHandler.handleRequest(req, null);

    // assert
    assertThat(res.getStatusCode()).isEqualTo(200);
    assertThat(res.getHeaders()).containsEntry("Content-Type", "application/json; charset=utf-8");

    var episodesAdded =
        objectMapper.readValue(res.getBody(), SyncEpisodesHandler.SyncEpisodesResponse.class);
    assertThat(episodesAdded.episodesAdded()).isEqualTo(3);

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

    assertThat(items).contains(ImmersionTrackerItem.createShow(user, "show1"));
    assertThat(items)
        .contains(ImmersionTrackerItem.createEpisode(user, "show1", "episode1", Instant.EPOCH));
    assertThat(items)
        .contains(ImmersionTrackerItem.createEpisode(user, "show1", "episode2", clock.now()));

    assertThat(items).contains(ImmersionTrackerItem.createShow(user, "show2"));
    assertThat(items)
        .contains(ImmersionTrackerItem.createEpisode(user, "show2", "episode1", Instant.EPOCH));
    assertThat(items)
        .contains(ImmersionTrackerItem.createEpisode(user, "show2", "episode2", clock.now()));
    assertThat(items)
        .contains(ImmersionTrackerItem.createEpisode(user, "show2", "episode3", clock.now()));
  }
}
