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
public class FindTripsHandlerIntegrationTest {
  private FakeClock fakeClock;
  private ObjectMapper objectMapper;
  private DynamoDbTable<PackingListItem> packingListTable;

  private FindTripsHandler findTripsHandler;

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

    findTripsHandler = new FindTripsHandler(factory);
  }

  @Test
  void handleRequestShouldReturnEmptyListWhenNoTrips() throws Exception {
    // arrange
    var event =
        APIGatewayV2HTTPEvent.builder().withQueryStringParameters(Map.of("user", "alice")).build();

    // act
    var response = findTripsHandler.handleRequest(event, null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(200);
    assertThat(response.getHeaders())
        .containsEntry("Content-Type", "application/json; charset=utf-8");

    var responseBody =
        objectMapper.readValue(response.getBody(), FindTripsHandler.FindTripsResponse.class);
    assertThat(responseBody.trips()).isEmpty();
  }

  @Test
  void handleRequestShouldReturnTripsOrderedByDepartureDateDescending() throws Exception {
    // arrange
    var now = Instant.ofEpochSecond(1700000000);
    fakeClock.setTime(now);

    var trip1 =
        PackingListItem.create(
            "alice",
            "trip-1",
            "Japan 2026",
            "Tokyo",
            LocalDate.of(2026, 1, 12),
            LocalDate.of(2026, 1, 26),
            List.of(),
            now,
            now);
    var trip2 =
        PackingListItem.create(
            "alice",
            "trip-2",
            "Europe 2026",
            "Paris",
            LocalDate.of(2026, 6, 1),
            LocalDate.of(2026, 6, 15),
            List.of(),
            now,
            now);
    var trip3 =
        PackingListItem.create(
            "alice",
            "trip-3",
            "Australia 2025",
            "Sydney",
            LocalDate.of(2025, 12, 20),
            LocalDate.of(2025, 12, 30),
            List.of(),
            now,
            now);

    packingListTable.putItem(trip1);
    packingListTable.putItem(trip2);
    packingListTable.putItem(trip3);

    var event =
        APIGatewayV2HTTPEvent.builder().withQueryStringParameters(Map.of("user", "alice")).build();

    // act
    var response = findTripsHandler.handleRequest(event, null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(200);

    var responseBody =
        objectMapper.readValue(response.getBody(), FindTripsHandler.FindTripsResponse.class);
    assertThat(responseBody.trips()).hasSize(3);

    // should be ordered by departure date descending
    assertThat(responseBody.trips().get(0).tripId()).isEqualTo("trip-2");
    assertThat(responseBody.trips().get(0).name()).isEqualTo("Europe 2026");
    assertThat(responseBody.trips().get(0).departureDate()).isEqualTo("2026-06-01");

    assertThat(responseBody.trips().get(1).tripId()).isEqualTo("trip-1");
    assertThat(responseBody.trips().get(1).name()).isEqualTo("Japan 2026");
    assertThat(responseBody.trips().get(1).departureDate()).isEqualTo("2026-01-12");

    assertThat(responseBody.trips().get(2).tripId()).isEqualTo("trip-3");
    assertThat(responseBody.trips().get(2).name()).isEqualTo("Australia 2025");
    assertThat(responseBody.trips().get(2).departureDate()).isEqualTo("2025-12-20");
  }

  @Test
  void handleRequestShouldOnlyReturnTripsForRequestedUser() throws Exception {
    // arrange
    var now = Instant.ofEpochSecond(1700000000);
    fakeClock.setTime(now);

    var aliceTrip =
        PackingListItem.create(
            "alice",
            "trip-1",
            "Alice Trip",
            "Tokyo",
            LocalDate.of(2026, 1, 12),
            LocalDate.of(2026, 1, 26),
            List.of(),
            now,
            now);
    var bobTrip =
        PackingListItem.create(
            "bob",
            "trip-2",
            "Bob Trip",
            "Paris",
            LocalDate.of(2026, 6, 1),
            LocalDate.of(2026, 6, 15),
            List.of(),
            now,
            now);

    packingListTable.putItem(aliceTrip);
    packingListTable.putItem(bobTrip);

    var event =
        APIGatewayV2HTTPEvent.builder().withQueryStringParameters(Map.of("user", "alice")).build();

    // act
    var response = findTripsHandler.handleRequest(event, null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(200);

    var responseBody =
        objectMapper.readValue(response.getBody(), FindTripsHandler.FindTripsResponse.class);
    assertThat(responseBody.trips()).hasSize(1);
    assertThat(responseBody.trips().get(0).tripId()).isEqualTo("trip-1");
    assertThat(responseBody.trips().get(0).name()).isEqualTo("Alice Trip");
  }

  @Test
  void handleRequestShouldReturnTripSummariesWithoutItems() throws Exception {
    // arrange
    var now = Instant.ofEpochSecond(1700000000);
    fakeClock.setTime(now);

    var tripItems =
        List.of(
            TripItem.create(
                "passport", "travel", 1, List.of("hand luggage"), TripItemStatus.UNPACKED),
            TripItem.create("toothbrush", "toiletries", 1, List.of(), TripItemStatus.PACKED));

    var trip =
        PackingListItem.create(
            "alice",
            "trip-1",
            "Japan 2026",
            "Tokyo",
            LocalDate.of(2026, 1, 12),
            LocalDate.of(2026, 1, 26),
            tripItems,
            now,
            now);

    packingListTable.putItem(trip);

    var event =
        APIGatewayV2HTTPEvent.builder().withQueryStringParameters(Map.of("user", "alice")).build();

    // act
    var response = findTripsHandler.handleRequest(event, null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(200);

    var responseBody =
        objectMapper.readValue(response.getBody(), FindTripsHandler.FindTripsResponse.class);
    assertThat(responseBody.trips()).hasSize(1);

    var tripSummary = responseBody.trips().get(0);
    assertThat(tripSummary.tripId()).isEqualTo("trip-1");
    assertThat(tripSummary.name()).isEqualTo("Japan 2026");
    assertThat(tripSummary.destination()).isEqualTo("Tokyo");
    assertThat(tripSummary.departureDate()).isEqualTo("2026-01-12");
    assertThat(tripSummary.returnDate()).isEqualTo("2026-01-26");
    assertThat(tripSummary.createdAt()).isEqualTo(1700000000);
    assertThat(tripSummary.updatedAt()).isEqualTo(1700000000);

    // verify no items field in the JSON response
    var jsonNode = objectMapper.readTree(response.getBody());
    assertThat(jsonNode.get("trips").get(0).has("items")).isFalse();
  }
}
