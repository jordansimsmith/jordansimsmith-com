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
public class SyncMoviesHandlerIntegrationTest {
  private FakeClock clock;
  private ObjectMapper objectMapper;
  private DynamoDbTable<ImmersionTrackerItem> immersionTrackerTable;
  private FakeTvdbClient fakeTvdbClient;

  private SyncMoviesHandler syncMoviesHandler;

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
    fakeTvdbClient = factory.fakeTvdbClient();

    DynamoDbUtils.reset(factory.dynamoDbClient());

    syncMoviesHandler = new SyncMoviesHandler(factory);
  }

  @Test
  void handleRequestShouldSyncMovies() throws Exception {
    // arrange
    clock.setTime(Instant.ofEpochMilli(123_000));
    var user = "alice";

    fakeTvdbClient.addMovie(new TvdbClient.Movie(1, "My Movie", "image1", Duration.ofMinutes(120)));
    fakeTvdbClient.addMovie(
        new TvdbClient.Movie(2, "Another Movie", "image2", Duration.ofMinutes(90)));

    var movie1 = new SyncMoviesHandler.Movie("movie1", 1);
    var movie2 = new SyncMoviesHandler.Movie("movie2", 2);
    var movies = List.of(movie1, movie2);
    var body = objectMapper.writeValueAsString(new SyncMoviesHandler.SyncMoviesRequest(movies));

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
    var res = syncMoviesHandler.handleRequest(req, null);

    // assert
    assertThat(res.getStatusCode()).isEqualTo(200);
    assertThat(res.getHeaders()).containsEntry("Content-Type", "application/json; charset=utf-8");

    var moviesAdded =
        objectMapper.readValue(res.getBody(), SyncMoviesHandler.SyncMoviesResponse.class);
    assertThat(moviesAdded.moviesAdded()).isEqualTo(2);

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

    var movieItem1 =
        ImmersionTrackerItem.createMovie(
            user, "movie1", 1, "My Movie", "image1", Duration.ofMinutes(120), clock.now());
    var movieItem2 =
        ImmersionTrackerItem.createMovie(
            user, "movie2", 2, "Another Movie", "image2", Duration.ofMinutes(90), clock.now());

    assertThat(items).contains(movieItem1);
    assertThat(items).contains(movieItem2);
  }

  @Test
  void handleRequestShouldSkipDuplicateMovies() throws Exception {
    // arrange
    clock.setTime(Instant.ofEpochMilli(123_000));
    var user = "alice";

    fakeTvdbClient.addMovie(new TvdbClient.Movie(1, "My Movie", "image1", Duration.ofMinutes(120)));

    var existingMovie =
        ImmersionTrackerItem.createMovie(
            user,
            "movie1",
            1,
            "Existing Movie",
            "existing-image",
            Duration.ofMinutes(120),
            Instant.EPOCH);
    immersionTrackerTable.putItem(existingMovie);

    var movies = List.of(new SyncMoviesHandler.Movie("movie1", 1));
    var body = objectMapper.writeValueAsString(new SyncMoviesHandler.SyncMoviesRequest(movies));

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
    var res = syncMoviesHandler.handleRequest(req, null);

    // assert
    assertThat(res.getStatusCode()).isEqualTo(200);

    var moviesAdded =
        objectMapper.readValue(res.getBody(), SyncMoviesHandler.SyncMoviesResponse.class);
    assertThat(moviesAdded.moviesAdded()).isEqualTo(0);

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

    assertThat(items).hasSize(1);
    assertThat(items.get(0)).isEqualTo(existingMovie);
  }
}
