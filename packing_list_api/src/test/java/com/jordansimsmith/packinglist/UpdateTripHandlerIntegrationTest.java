package com.jordansimsmith.packinglist;

import static org.assertj.core.api.Assertions.assertThat;

import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jordansimsmith.dynamodb.DynamoDbContainer;
import com.jordansimsmith.dynamodb.DynamoDbUtils;
import com.jordansimsmith.time.FakeClock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;

@Testcontainers
public class UpdateTripHandlerIntegrationTest {
  private FakeClock fakeClock;
  private ObjectMapper objectMapper;
  private DynamoDbTable<PackingListItem> packingListTable;

  private UpdateTripHandler updateTripHandler;

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

    fakeClock = factory.fakeClock();
    objectMapper = factory.objectMapper();
    packingListTable = factory.packingListTable();

    DynamoDbUtils.reset(factory.dynamoDbClient());

    updateTripHandler = new UpdateTripHandler(factory);
  }

  @Test
  void handleRequestShouldUpdateTripAndPreserveCreatedAt() throws Exception {
    // arrange
    var createdAt = Instant.ofEpochSecond(1700000000);
    var updatedAt = Instant.ofEpochSecond(1700100000);
    var tripId = "test-trip-123";

    var existingItem =
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
    packingListTable.putItem(existingItem);

    fakeClock.setTime(updatedAt);

    var requestBody =
        """
        {
          "trip_id": "test-trip-123",
          "name": "Japan 2026 Updated",
          "destination": "Osaka",
          "departure_date": "2026-01-15",
          "return_date": "2026-01-30",
          "items": [
            {
              "name": "passport",
              "category": "travel",
              "quantity": 1,
              "tags": ["hand luggage"],
              "status": "packed"
            },
            {
              "name": "toothbrush",
              "category": "toiletries",
              "quantity": 1,
              "tags": [],
              "status": "unpacked"
            }
          ]
        }
        """;

    var event =
        APIGatewayV2HTTPEvent.builder()
            .withQueryStringParameters(Map.of("user", "alice"))
            .withPathParameters(Map.of("trip_id", tripId))
            .withBody(requestBody)
            .build();

    // act
    var response = updateTripHandler.handleRequest(event, null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(200);
    assertThat(response.getHeaders())
        .containsEntry("Content-Type", "application/json; charset=utf-8");

    var responseBody =
        objectMapper.readValue(response.getBody(), UpdateTripHandler.UpdateTripResponse.class);
    assertThat(responseBody.trip()).isNotNull();
    assertThat(responseBody.trip().tripId()).isEqualTo(tripId);
    assertThat(responseBody.trip().name()).isEqualTo("Japan 2026 Updated");
    assertThat(responseBody.trip().destination()).isEqualTo("Osaka");
    assertThat(responseBody.trip().departureDate()).isEqualTo("2026-01-15");
    assertThat(responseBody.trip().returnDate()).isEqualTo("2026-01-30");
    assertThat(responseBody.trip().createdAt()).isEqualTo(1700000000);
    assertThat(responseBody.trip().updatedAt()).isEqualTo(1700100000);
    assertThat(responseBody.trip().items()).hasSize(2);

    // verify stored in DynamoDB
    var items = packingListTable.scan().items().stream().toList();
    assertThat(items).hasSize(1);

    var storedItem = items.get(0);
    assertThat(storedItem.getUser()).isEqualTo("alice");
    assertThat(storedItem.getTripId()).isEqualTo(tripId);
    assertThat(storedItem.getName()).isEqualTo("Japan 2026 Updated");
    assertThat(storedItem.getDestination()).isEqualTo("Osaka");
    assertThat(storedItem.getDepartureDate()).isEqualTo(LocalDate.of(2026, 1, 15));
    assertThat(storedItem.getReturnDate()).isEqualTo(LocalDate.of(2026, 1, 30));
    assertThat(storedItem.getCreatedAt()).isEqualTo(createdAt);
    assertThat(storedItem.getUpdatedAt()).isEqualTo(updatedAt);
    assertThat(storedItem.getGsi1sk()).isEqualTo("DEPARTURE#2026-01-15#TRIP#" + tripId);
    assertThat(storedItem.getItems()).hasSize(2);
  }

  @Test
  void handleRequestShouldReturnNotFoundWhenTripDoesNotExist() throws Exception {
    // arrange
    var requestBody =
        """
        {
          "trip_id": "non-existent-trip",
          "name": "Japan 2026",
          "destination": "Tokyo",
          "departure_date": "2026-01-12",
          "return_date": "2026-01-26",
          "items": [
            {
              "name": "passport",
              "category": "travel",
              "quantity": 1,
              "tags": [],
              "status": "unpacked"
            }
          ]
        }
        """;

    var event =
        APIGatewayV2HTTPEvent.builder()
            .withQueryStringParameters(Map.of("user", "alice"))
            .withPathParameters(Map.of("trip_id", "non-existent-trip"))
            .withBody(requestBody)
            .build();

    // act
    var response = updateTripHandler.handleRequest(event, null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(404);
    var responseBody =
        objectMapper.readValue(response.getBody(), UpdateTripHandler.ErrorResponse.class);
    assertThat(responseBody.message()).isEqualTo("Not Found");
  }

  @Test
  void handleRequestShouldReturnBadRequestWhenTripIdMismatch() throws Exception {
    // arrange
    var tripId = "test-trip-123";
    var existingItem =
        PackingListItem.create(
            "alice",
            tripId,
            "Japan 2026",
            "Tokyo",
            LocalDate.of(2026, 1, 12),
            LocalDate.of(2026, 1, 26),
            List.of(TripItem.create("passport", "travel", 1, List.of(), TripItemStatus.UNPACKED)),
            Instant.ofEpochSecond(1700000000),
            Instant.ofEpochSecond(1700000000));
    packingListTable.putItem(existingItem);

    var requestBody =
        """
        {
          "trip_id": "different-trip-id",
          "name": "Japan 2026",
          "destination": "Tokyo",
          "departure_date": "2026-01-12",
          "return_date": "2026-01-26",
          "items": [
            {
              "name": "passport",
              "category": "travel",
              "quantity": 1,
              "tags": [],
              "status": "unpacked"
            }
          ]
        }
        """;

    var event =
        APIGatewayV2HTTPEvent.builder()
            .withQueryStringParameters(Map.of("user", "alice"))
            .withPathParameters(Map.of("trip_id", tripId))
            .withBody(requestBody)
            .build();

    // act
    var response = updateTripHandler.handleRequest(event, null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(400);
    var responseBody =
        objectMapper.readValue(response.getBody(), UpdateTripHandler.ErrorResponse.class);
    assertThat(responseBody.message()).isEqualTo("trip_id mismatch");
  }

  @Test
  void handleRequestShouldReturnBadRequestWhenValidationFails() throws Exception {
    // arrange
    var tripId = "test-trip-123";
    var existingItem =
        PackingListItem.create(
            "alice",
            tripId,
            "Japan 2026",
            "Tokyo",
            LocalDate.of(2026, 1, 12),
            LocalDate.of(2026, 1, 26),
            List.of(TripItem.create("passport", "travel", 1, List.of(), TripItemStatus.UNPACKED)),
            Instant.ofEpochSecond(1700000000),
            Instant.ofEpochSecond(1700000000));
    packingListTable.putItem(existingItem);

    var requestBody =
        """
        {
          "trip_id": "test-trip-123",
          "name": "Japan 2026",
          "destination": "Tokyo",
          "departure_date": "2026-01-12",
          "return_date": "2026-01-26",
          "items": [
            {
              "name": "passport",
              "category": "travel",
              "quantity": 0,
              "tags": [],
              "status": "unpacked"
            }
          ]
        }
        """;

    var event =
        APIGatewayV2HTTPEvent.builder()
            .withQueryStringParameters(Map.of("user", "alice"))
            .withPathParameters(Map.of("trip_id", tripId))
            .withBody(requestBody)
            .build();

    // act
    var response = updateTripHandler.handleRequest(event, null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(400);
    var responseBody =
        objectMapper.readValue(response.getBody(), UpdateTripHandler.ErrorResponse.class);
    assertThat(responseBody.message()).isEqualTo("quantity must be >= 1");
  }

  @Test
  void handleRequestShouldUpdateGsi1skWhenDepartureDateChanges() throws Exception {
    // arrange
    var createdAt = Instant.ofEpochSecond(1700000000);
    var updatedAt = Instant.ofEpochSecond(1700100000);
    var tripId = "test-trip-456";

    var existingItem =
        PackingListItem.create(
            "bob",
            tripId,
            "Berlin Trip",
            "Berlin",
            LocalDate.of(2026, 3, 1),
            LocalDate.of(2026, 3, 10),
            List.of(TripItem.create("passport", "travel", 1, List.of(), TripItemStatus.UNPACKED)),
            createdAt,
            createdAt);
    packingListTable.putItem(existingItem);

    fakeClock.setTime(updatedAt);

    var requestBody =
        """
        {
          "trip_id": "test-trip-456",
          "name": "Berlin Trip",
          "destination": "Berlin",
          "departure_date": "2026-04-15",
          "return_date": "2026-04-25",
          "items": [
            {
              "name": "passport",
              "category": "travel",
              "quantity": 1,
              "tags": [],
              "status": "unpacked"
            }
          ]
        }
        """;

    var event =
        APIGatewayV2HTTPEvent.builder()
            .withQueryStringParameters(Map.of("user", "bob"))
            .withPathParameters(Map.of("trip_id", tripId))
            .withBody(requestBody)
            .build();

    // act
    var response = updateTripHandler.handleRequest(event, null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(200);

    var items = packingListTable.scan().items().stream().toList();
    assertThat(items).hasSize(1);

    var storedItem = items.get(0);
    assertThat(storedItem.getDepartureDate()).isEqualTo(LocalDate.of(2026, 4, 15));
    assertThat(storedItem.getGsi1sk()).isEqualTo("DEPARTURE#2026-04-15#TRIP#" + tripId);
  }

  @Test
  void handleRequestShouldReturnBadRequestWhenDuplicateItemNames() throws Exception {
    // arrange
    var tripId = "test-trip-123";
    var existingItem =
        PackingListItem.create(
            "alice",
            tripId,
            "Japan 2026",
            "Tokyo",
            LocalDate.of(2026, 1, 12),
            LocalDate.of(2026, 1, 26),
            List.of(TripItem.create("passport", "travel", 1, List.of(), TripItemStatus.UNPACKED)),
            Instant.ofEpochSecond(1700000000),
            Instant.ofEpochSecond(1700000000));
    packingListTable.putItem(existingItem);

    var requestBody =
        """
        {
          "trip_id": "test-trip-123",
          "name": "Japan 2026",
          "destination": "Tokyo",
          "departure_date": "2026-01-12",
          "return_date": "2026-01-26",
          "items": [
            {
              "name": "Passport",
              "category": "travel",
              "quantity": 1,
              "tags": [],
              "status": "unpacked"
            },
            {
              "name": "passport",
              "category": "travel",
              "quantity": 1,
              "tags": [],
              "status": "unpacked"
            }
          ]
        }
        """;

    var event =
        APIGatewayV2HTTPEvent.builder()
            .withQueryStringParameters(Map.of("user", "alice"))
            .withPathParameters(Map.of("trip_id", tripId))
            .withBody(requestBody)
            .build();

    // act
    var response = updateTripHandler.handleRequest(event, null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(400);
    var responseBody =
        objectMapper.readValue(response.getBody(), UpdateTripHandler.ErrorResponse.class);
    assertThat(responseBody.message()).isEqualTo("duplicate item name: passport");
  }
}
