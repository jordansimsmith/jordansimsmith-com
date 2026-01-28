package com.jordansimsmith.immersiontracker;

import static org.assertj.core.api.Assertions.assertThat;

import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jordansimsmith.dynamodb.DynamoDbContainer;
import com.jordansimsmith.dynamodb.DynamoDbUtils;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;

@Testcontainers
public class UpdateShowHandlerIntegrationTest {
  private FakeTvdbClient tvdbClient;
  private ObjectMapper objectMapper;
  private DynamoDbTable<ImmersionTrackerItem> immersionTrackerTable;

  private UpdateShowHandler updateShowHandler;

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

    tvdbClient = factory.fakeTvdbClient();
    objectMapper = factory.objectMapper();
    immersionTrackerTable = factory.immersionTrackerTable();

    DynamoDbUtils.reset(factory.dynamoDbClient());

    updateShowHandler = new UpdateShowHandler(factory);
  }

  @Test
  void handleRequestShouldUpdateShow() throws Exception {
    // arrange
    var user = "alice";
    var show1 = ImmersionTrackerItem.createShow(user, "show1");
    immersionTrackerTable.putItem(show1);

    var tvdbShow = new TvdbClient.Show(123, "my show", "my image", Duration.ofMinutes(45));
    tvdbClient.addShow(tvdbShow);

    var body = new UpdateShowHandler.UpdateShowRequest(show1.getFolderName(), tvdbShow.id());
    var authHeader =
        "Basic "
            + Base64.getEncoder()
                .encodeToString((user + ":password").getBytes(StandardCharsets.UTF_8));
    var req =
        APIGatewayV2HTTPEvent.builder()
            .withHeaders(Map.of("Authorization", authHeader))
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
    assertThat(updatedShow.getTvdbAverageRuntime()).isEqualTo(Duration.ofMinutes(45));
    assertThat(updatedShow.getVersion()).isEqualTo(2);
  }
}
