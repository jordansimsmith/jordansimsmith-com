package com.jordansimsmith.immersiontracker;

import static org.assertj.core.api.Assertions.assertThat;

import com.jordansimsmith.testcontainers.DynamoDbContainer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.services.dynamodb.waiters.DynamoDbWaiter;

@Testcontainers
public class GetProgressHandlerIntegrationTest {
  private DynamoDbTable<ImmersionTrackerItem> immersionTrackerTable;

  private GetProgressHandler getProgressHandler;

  @Container DynamoDbContainer dynamoDbContainer = new DynamoDbContainer();

  @BeforeEach
  void setUp() {
    var factory =
        ImmersionTrackerFactory.builder().dynamoDbEndpoint(dynamoDbContainer.getEndpoint()).build();

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
  void handleRequestShouldQueryItems() {
    // arrange
    var episode1 = ImmersionTrackerItem.createEpisode("jordansimsmith", "show1", "episode1", 123);
    var episode2 = ImmersionTrackerItem.createEpisode("jordansimsmith", "show1", "episode2", 456);
    var show = ImmersionTrackerItem.createShow("jordansimsmith", "show1");

    immersionTrackerTable.putItem(episode1);
    immersionTrackerTable.putItem(episode2);
    immersionTrackerTable.putItem(show);

    // act
    var res = getProgressHandler.handleRequest(null, null);

    // assert
    assertThat(res).contains(episode1);
    assertThat(res).contains(episode2);
    assertThat(res).contains(show);
    assertThat(res).hasSize(3);
  }
}
