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
public class GetTripHandlerIntegrationTest {
  private FakeClock fakeClock;
  private ObjectMapper objectMapper;
  private DynamoDbTable<PackingListItem> packingListTable;

  private GetTripHandler getTripHandler;

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

    getTripHandler = new GetTripHandler(factory);
  }

  @Test
  void handleRequestShouldReturnTripWhenExists() throws Exception {
    // arrange
    var tripId = "test-trip-id-123";
    var createdAt = Instant.ofEpochSecond(1700000000);
    var updatedAt = Instant.ofEpochSecond(1700001000);

    var tripItems =
        List.of(
            TripItem.create(
                "passport", "travel", 1, List.of("hand luggage"), TripItemStatus.UNPACKED),
            TripItem.create("toothbrush", "toiletries", 1, List.of(), TripItemStatus.PACKED));

    var packingListItem =
        PackingListItem.create(
            "alice",
            tripId,
            "Japan 2026",
            "Tokyo",
            LocalDate.of(2026, 1, 12),
            LocalDate.of(2026, 1, 26),
            tripItems,
            createdAt,
            updatedAt);

    packingListTable.putItem(packingListItem);

    var event =
        APIGatewayV2HTTPEvent.builder()
            .withQueryStringParameters(Map.of("user", "alice"))
            .withPathParameters(Map.of("trip_id", tripId))
            .build();

    // act
    var response = getTripHandler.handleRequest(event, null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(200);
    assertThat(response.getHeaders())
        .containsEntry("Content-Type", "application/json; charset=utf-8");

    var responseBody =
        objectMapper.readValue(response.getBody(), GetTripHandler.GetTripResponse.class);
    assertThat(responseBody.trip()).isNotNull();
    assertThat(responseBody.trip().tripId()).isEqualTo(tripId);
    assertThat(responseBody.trip().name()).isEqualTo("Japan 2026");
    assertThat(responseBody.trip().destination()).isEqualTo("Tokyo");
    assertThat(responseBody.trip().departureDate()).isEqualTo("2026-01-12");
    assertThat(responseBody.trip().returnDate()).isEqualTo("2026-01-26");
    assertThat(responseBody.trip().createdAt()).isEqualTo(1700000000);
    assertThat(responseBody.trip().updatedAt()).isEqualTo(1700001000);
    assertThat(responseBody.trip().items()).hasSize(2);

    var passportItem =
        responseBody.trip().items().stream()
            .filter(item -> item.name().equals("passport"))
            .findFirst()
            .orElseThrow();
    assertThat(passportItem.category()).isEqualTo("travel");
    assertThat(passportItem.quantity()).isEqualTo(1);
    assertThat(passportItem.tags()).containsExactly("hand luggage");
    assertThat(passportItem.status()).isEqualTo("unpacked");

    var toothbrushItem =
        responseBody.trip().items().stream()
            .filter(item -> item.name().equals("toothbrush"))
            .findFirst()
            .orElseThrow();
    assertThat(toothbrushItem.status()).isEqualTo("packed");
  }

  @Test
  void handleRequestShouldReturn404WhenTripNotFound() throws Exception {
    // arrange
    var event =
        APIGatewayV2HTTPEvent.builder()
            .withQueryStringParameters(Map.of("user", "alice"))
            .withPathParameters(Map.of("trip_id", "nonexistent-trip-id"))
            .build();

    // act
    var response = getTripHandler.handleRequest(event, null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(404);
    assertThat(response.getHeaders())
        .containsEntry("Content-Type", "application/json; charset=utf-8");

    var responseBody =
        objectMapper.readValue(response.getBody(), GetTripHandler.ErrorResponse.class);
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

    var event =
        APIGatewayV2HTTPEvent.builder()
            .withQueryStringParameters(Map.of("user", "alice"))
            .withPathParameters(Map.of("trip_id", tripId))
            .build();

    // act
    var response = getTripHandler.handleRequest(event, null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(404);
    var responseBody =
        objectMapper.readValue(response.getBody(), GetTripHandler.ErrorResponse.class);
    assertThat(responseBody.message()).isEqualTo("Not Found");
  }

  @Test
  void handleRequestShouldReturnTripWithAllItemStatuses() throws Exception {
    // arrange
    var tripId = "status-test-trip";
    var createdAt = Instant.ofEpochSecond(1700000000);

    var tripItems =
        List.of(
            TripItem.create("item1", "cat1", 1, List.of(), TripItemStatus.UNPACKED),
            TripItem.create("item2", "cat2", 2, List.of("tag1"), TripItemStatus.PACKED),
            TripItem.create(
                "item3", "cat3", 3, List.of("tag2", "tag3"), TripItemStatus.PACK_JUST_IN_TIME));

    var packingListItem =
        PackingListItem.create(
            "alice",
            tripId,
            "Status Test",
            "London",
            LocalDate.of(2026, 3, 1),
            LocalDate.of(2026, 3, 5),
            tripItems,
            createdAt,
            createdAt);

    packingListTable.putItem(packingListItem);

    var event =
        APIGatewayV2HTTPEvent.builder()
            .withQueryStringParameters(Map.of("user", "alice"))
            .withPathParameters(Map.of("trip_id", tripId))
            .build();

    // act
    var response = getTripHandler.handleRequest(event, null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(200);

    var responseBody =
        objectMapper.readValue(response.getBody(), GetTripHandler.GetTripResponse.class);
    assertThat(responseBody.trip().items()).hasSize(3);

    var statuses = responseBody.trip().items().stream().map(Trip.Item::status).toList();
    assertThat(statuses).containsExactlyInAnyOrder("unpacked", "packed", "pack-just-in-time");
  }
}
