package com.jordansimsmith.immersiontracker;

import static org.assertj.core.api.Assertions.assertThat;

import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jordansimsmith.testcontainers.DynamoDbContainer;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.services.dynamodb.waiters.DynamoDbWaiter;

@Testcontainers
public class UpdateShowHandlerIntegrationTest {
  private FakeTvdbClient tvdbClient;
  private ObjectMapper objectMapper;
  private DynamoDbTable<ImmersionTrackerItem> immersionTrackerTable;

  private UpdateShowHandler updateShowHandler;

  @Container DynamoDbContainer dynamoDbContainer = new DynamoDbContainer();

  @BeforeEach
  void setUp() {
    var factory = ImmersionTrackerTestFactory.create(dynamoDbContainer.getEndpoint());

    tvdbClient = factory.fakeTvdbClient();
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

    updateShowHandler = new UpdateShowHandler(factory);
  }

  @Test
  void handleRequestShouldUpdateShow() throws Exception {
    // arrange
    var user = "alice";
    var show1 = ImmersionTrackerItem.createShow(user, "show1");
    immersionTrackerTable.putItem(show1);

    var tvdbShow = new TvdbClient.Show(123, "my show", "my image");
    tvdbClient.addShow(tvdbShow);

    var body = new UpdateShowHandler.UpdateShowRequest(show1.getFolderName(), tvdbShow.id());
    var req =
        APIGatewayV2HTTPEvent.builder()
            .withQueryStringParameters(Map.of("user", user))
            .withBody(objectMapper.writeValueAsString(body))
            .build();

    // act
    var res = updateShowHandler.handleRequest(req, null);

    // assert
    assertThat(res.getStatusCode()).isEqualTo(200);
    assertThat(res.getHeaders()).containsEntry("Content-Type", "application/json; charset=utf-8");

    var updatedShow =
        immersionTrackerTable.getItem(
            Key.builder()
                .partitionValue(ImmersionTrackerItem.formatPk(user))
                .sortValue(ImmersionTrackerItem.formatShowSk(show1.getFolderName()))
                .build());
    assertThat(updatedShow.getTvdbId()).isEqualTo(tvdbShow.id());
    assertThat(updatedShow.getTvdbName()).isEqualTo(tvdbShow.name());
    assertThat(updatedShow.getTvdbImage()).isEqualTo(tvdbShow.image());
    assertThat(updatedShow.getVersion()).isEqualTo(2);
  }
}
