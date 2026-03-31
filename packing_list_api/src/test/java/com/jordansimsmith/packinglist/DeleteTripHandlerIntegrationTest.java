package com.jordansimsmith.packinglist;

import static org.assertj.core.api.Assertions.assertThat;

import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jordansimsmith.dynamodb.DynamoDbContainer;
import com.jordansimsmith.dynamodb.DynamoDbUtils;
import java.nio.charset.StandardCharsets;
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

@Testcontainers
public class DeleteTripHandlerIntegrationTest {
  private ObjectMapper objectMapper;
  private DynamoDbTable<PackingListItem> packingListTable;

  private DeleteTripHandler deleteTripHandler;

  @Container private static final DynamoDbContainer dynamoDbContainer = new DynamoDbContainer();

  @BeforeAll
  static void setUpBeforeClass() {
    var factory = PackingListTestFactory.create(dynamoDbContainer.getEndpoint());
    var table = factory.packingListTable();
    DynamoDbUtils.createTable(factory.dynamoDbClient(), table);
  }

  @BeforeEach
  void setUp() {
    var factory = PackingListTestFactory.create(dynamoDbContainer.getEndpoint());

    objectMapper = factory.objectMapper();
    packingListTable = factory.packingListTable();

    DynamoDbUtils.reset(factory.dynamoDbClient());

    deleteTripHandler = new DeleteTripHandler(factory);
  }

  @Test
  void handleRequestShouldReturn204WhenTripExistsAndIsDeleted() throws Exception {
    // arrange
    var tripId = "test-trip-id-123";
    var createdAt = Instant.ofEpochSecond(1700000000);

    var packingListItem =
        PackingListItem.create(
            "alice",
            tripId,
            "Japan 2026",
            "Tokyo",
            LocalDate.of(2026, 1, 12),
            LocalDate.of(2026, 1, 26),
            List.of(
                TripItem.create(
                    "passport", "travel", 1, List.of("hand luggage"), TripItemStatus.UNPACKED)),
            createdAt,
            createdAt);

    packingListTable.putItem(packingListItem);

    var authHeader =
        "Basic "
            + Base64.getEncoder().encodeToString("alice:password".getBytes(StandardCharsets.UTF_8));
    var event =
        APIGatewayV2HTTPEvent.builder()
            .withHeaders(Map.of("Authorization", authHeader))
            .withPathParameters(Map.of("trip_id", tripId))
            .build();

    // act
    var response = deleteTripHandler.handleRequest(event, null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(204);
    assertThat(response.getHeaders())
        .containsEntry("Content-Type", "application/json; charset=utf-8");

    var scanResult = packingListTable.scan().items().stream().toList();
    assertThat(scanResult).isEmpty();
  }

  @Test
  void handleRequestShouldReturn404WhenTripNotFound() throws Exception {
    // arrange
    var authHeader =
        "Basic "
            + Base64.getEncoder().encodeToString("alice:password".getBytes(StandardCharsets.UTF_8));
    var event =
        APIGatewayV2HTTPEvent.builder()
            .withHeaders(Map.of("Authorization", authHeader))
            .withPathParameters(Map.of("trip_id", "nonexistent-trip-id"))
            .build();

    // act
    var response = deleteTripHandler.handleRequest(event, null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(404);
    assertThat(response.getHeaders())
        .containsEntry("Content-Type", "application/json; charset=utf-8");

    var responseBody =
        objectMapper.readValue(response.getBody(), DeleteTripHandler.ErrorResponse.class);
    assertThat(responseBody.message()).isEqualTo("Not Found");
  }

  @Test
  void handleRequestShouldReturn404WhenTripBelongsToDifferentUser() throws Exception {
    // arrange
    var tripId = "test-trip-id-456";
    var createdAt = Instant.ofEpochSecond(1700000000);

    var packingListItem =
        PackingListItem.create(
            "bob",
            tripId,
            "Bob's Trip",
            "Paris",
            LocalDate.of(2026, 2, 1),
            LocalDate.of(2026, 2, 10),
            List.of(),
            createdAt,
            createdAt);

    packingListTable.putItem(packingListItem);

    var authHeader =
        "Basic "
            + Base64.getEncoder().encodeToString("alice:password".getBytes(StandardCharsets.UTF_8));
    var event =
        APIGatewayV2HTTPEvent.builder()
            .withHeaders(Map.of("Authorization", authHeader))
            .withPathParameters(Map.of("trip_id", tripId))
            .build();

    // act
    var response = deleteTripHandler.handleRequest(event, null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(404);

    var responseBody =
        objectMapper.readValue(response.getBody(), DeleteTripHandler.ErrorResponse.class);
    assertThat(responseBody.message()).isEqualTo("Not Found");

    var scanResult = packingListTable.scan().items().stream().toList();
    assertThat(scanResult).hasSize(1);
  }
}
