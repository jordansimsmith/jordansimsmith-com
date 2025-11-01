package com.jordansimsmith.immersiontracker;

import static org.assertj.core.api.Assertions.assertThat;

import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jordansimsmith.dynamodb.DynamoDbContainer;
import com.jordansimsmith.dynamodb.DynamoDbUtils;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;

@Testcontainers
public class GetShowsHandlerIntegrationTest {
  private ObjectMapper objectMapper;
  private DynamoDbTable<ImmersionTrackerItem> immersionTrackerTable;

  private GetShowsHandler getShowsHandler;

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

    objectMapper = factory.objectMapper();
    immersionTrackerTable = factory.immersionTrackerTable();

    DynamoDbUtils.reset(factory.dynamoDbClient());

    getShowsHandler = new GetShowsHandler(factory);
  }

  @Test
  void handleRequestShouldGetShows() throws Exception {
    // arrange
    var user = "alice";
    var episode1 = ImmersionTrackerItem.createEpisode(user, "show1", "episode1", Instant.EPOCH);
    var show1 = ImmersionTrackerItem.createShow(user, "show1");
    show1.setTvdbId(123);
    show1.setTvdbName("my show");
    show1.setTvdbImage("my image");
    var show2 = ImmersionTrackerItem.createShow(user, "show2");

    immersionTrackerTable.putItem(episode1);
    immersionTrackerTable.putItem(show1);
    immersionTrackerTable.putItem(show2);

    // act
    var req =
        APIGatewayV2HTTPEvent.builder().withQueryStringParameters(Map.of("user", user)).build();
    var res = getShowsHandler.handleRequest(req, null);

    // assert
    assertThat(res.getStatusCode()).isEqualTo(200);
    assertThat(res.getHeaders()).containsEntry("Content-Type", "application/json; charset=utf-8");

    var shows = objectMapper.readValue(res.getBody(), GetShowsHandler.GetShowsResponse.class);
    assertThat(shows).isNotNull();
    assertThat(shows.shows()).hasSize(2);
    assertThat(shows.shows().get(0).folderName()).isEqualTo("show1");
    assertThat(shows.shows().get(0).tvdbId()).isEqualTo(123);
    assertThat(shows.shows().get(0).tvdbName()).isEqualTo("my show");
    assertThat(shows.shows().get(0).tvdbImage()).isEqualTo("my image");
    assertThat(shows.shows().get(1).folderName()).isEqualTo("show2");
  }
}
