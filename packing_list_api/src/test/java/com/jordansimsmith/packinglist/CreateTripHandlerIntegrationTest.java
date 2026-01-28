package com.jordansimsmith.packinglist;

import static org.assertj.core.api.Assertions.assertThat;

import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jordansimsmith.dynamodb.DynamoDbContainer;
import com.jordansimsmith.dynamodb.DynamoDbUtils;
import com.jordansimsmith.time.FakeClock;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;

@Testcontainers
public class CreateTripHandlerIntegrationTest {
  private FakeClock fakeClock;
  private ObjectMapper objectMapper;
  private DynamoDbTable<PackingListItem> packingListTable;

  private CreateTripHandler createTripHandler;

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

    createTripHandler = new CreateTripHandler(factory);
  }

  @Test
  void handleRequestShouldCreateTripAndStoreInDynamoDb() throws Exception {
    // arrange
    fakeClock.setTime(Instant.ofEpochSecond(1700000000));
    var requestBody =
        """
        {
          "name": "Japan 2026",
          "destination": "Tokyo",
          "departure_date": "2026-01-12",
          "return_date": "2026-01-26",
          "items": [
            {
              "name": "passport",
              "category": "travel",
              "quantity": 1,
              "tags": ["hand luggage"],
              "status": "unpacked"
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

    var authHeader =
        "Basic "
            + Base64.getEncoder().encodeToString("alice:password".getBytes(StandardCharsets.UTF_8));
    var event =
        APIGatewayV2HTTPEvent.builder()
            .withHeaders(Map.of("Authorization", authHeader))
            .withBody(requestBody)
            .build();

    // act
    var response = createTripHandler.handleRequest(event, null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(201);
    assertThat(response.getHeaders())
        .containsEntry("Content-Type", "application/json; charset=utf-8");

    var responseBody =
        objectMapper.readValue(response.getBody(), CreateTripHandler.CreateTripResponse.class);
    assertThat(responseBody.trip()).isNotNull();
    assertThat(responseBody.trip().tripId()).isNotNull();
    assertThat(responseBody.trip().name()).isEqualTo("Japan 2026");
    assertThat(responseBody.trip().destination()).isEqualTo("Tokyo");
    assertThat(responseBody.trip().departureDate()).isEqualTo("2026-01-12");
    assertThat(responseBody.trip().returnDate()).isEqualTo("2026-01-26");
    assertThat(responseBody.trip().createdAt()).isEqualTo(1700000000);
    assertThat(responseBody.trip().updatedAt()).isEqualTo(1700000000);
    assertThat(responseBody.trip().items()).hasSize(2);

    // verify stored in DynamoDB
    var items = packingListTable.scan().items().stream().toList();
    assertThat(items).hasSize(1);

    var storedItem = items.get(0);
    assertThat(storedItem.getUser()).isEqualTo("alice");
    assertThat(storedItem.getTripId()).isEqualTo(responseBody.trip().tripId());
    assertThat(storedItem.getName()).isEqualTo("Japan 2026");
    assertThat(storedItem.getDestination()).isEqualTo("Tokyo");
    assertThat(storedItem.getDepartureDate()).isEqualTo(LocalDate.of(2026, 1, 12));
    assertThat(storedItem.getReturnDate()).isEqualTo(LocalDate.of(2026, 1, 26));
    assertThat(storedItem.getPk()).isEqualTo("USER#alice");
    assertThat(storedItem.getSk()).isEqualTo("TRIP#" + responseBody.trip().tripId());
    assertThat(storedItem.getGsi1pk()).isEqualTo("USER#alice");
    assertThat(storedItem.getGsi1sk())
        .isEqualTo("DEPARTURE#2026-01-12#TRIP#" + responseBody.trip().tripId());
    assertThat(storedItem.getItems()).hasSize(2);
  }

  @Test
  void handleRequestShouldReturnBadRequestWhenNameMissing() throws Exception {
    // arrange
    var requestBody =
        """
        {
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

    var authHeader =
        "Basic "
            + Base64.getEncoder().encodeToString("alice:password".getBytes(StandardCharsets.UTF_8));
    var event =
        APIGatewayV2HTTPEvent.builder()
            .withHeaders(Map.of("Authorization", authHeader))
            .withBody(requestBody)
            .build();

    // act
    var response = createTripHandler.handleRequest(event, null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(400);
    var responseBody =
        objectMapper.readValue(response.getBody(), CreateTripHandler.ErrorResponse.class);
    assertThat(responseBody.message()).isEqualTo("name is required");
  }

  @Test
  void handleRequestShouldReturnBadRequestWhenQuantityLessThanOne() throws Exception {
    // arrange
    var requestBody =
        """
        {
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

    var authHeader =
        "Basic "
            + Base64.getEncoder().encodeToString("alice:password".getBytes(StandardCharsets.UTF_8));
    var event =
        APIGatewayV2HTTPEvent.builder()
            .withHeaders(Map.of("Authorization", authHeader))
            .withBody(requestBody)
            .build();

    // act
    var response = createTripHandler.handleRequest(event, null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(400);
    var responseBody =
        objectMapper.readValue(response.getBody(), CreateTripHandler.ErrorResponse.class);
    assertThat(responseBody.message()).isEqualTo("quantity must be >= 1");
  }

  @Test
  void handleRequestShouldReturnBadRequestWhenDuplicateItemNames() throws Exception {
    // arrange
    var requestBody =
        """
        {
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

    var authHeader =
        "Basic "
            + Base64.getEncoder().encodeToString("alice:password".getBytes(StandardCharsets.UTF_8));
    var event =
        APIGatewayV2HTTPEvent.builder()
            .withHeaders(Map.of("Authorization", authHeader))
            .withBody(requestBody)
            .build();

    // act
    var response = createTripHandler.handleRequest(event, null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(400);
    var responseBody =
        objectMapper.readValue(response.getBody(), CreateTripHandler.ErrorResponse.class);
    assertThat(responseBody.message()).isEqualTo("duplicate item name: passport");
  }

  @Test
  void handleRequestShouldReturnBadRequestWhenInvalidStatus() throws Exception {
    // arrange
    var requestBody =
        """
        {
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
              "status": "invalid-status"
            }
          ]
        }
        """;

    var authHeader =
        "Basic "
            + Base64.getEncoder().encodeToString("alice:password".getBytes(StandardCharsets.UTF_8));
    var event =
        APIGatewayV2HTTPEvent.builder()
            .withHeaders(Map.of("Authorization", authHeader))
            .withBody(requestBody)
            .build();

    // act
    var response = createTripHandler.handleRequest(event, null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(400);
    var responseBody =
        objectMapper.readValue(response.getBody(), CreateTripHandler.ErrorResponse.class);
    assertThat(responseBody.message()).isEqualTo("invalid item status: invalid-status");
  }

  @Test
  void handleRequestShouldReturnBadRequestWhenInvalidDateFormat() throws Exception {
    // arrange
    var requestBody =
        """
        {
          "name": "Japan 2026",
          "destination": "Tokyo",
          "departure_date": "12-01-2026",
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

    var authHeader =
        "Basic "
            + Base64.getEncoder().encodeToString("alice:password".getBytes(StandardCharsets.UTF_8));
    var event =
        APIGatewayV2HTTPEvent.builder()
            .withHeaders(Map.of("Authorization", authHeader))
            .withBody(requestBody)
            .build();

    // act
    var response = createTripHandler.handleRequest(event, null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(400);
    var responseBody =
        objectMapper.readValue(response.getBody(), CreateTripHandler.ErrorResponse.class);
    assertThat(responseBody.message()).isEqualTo("departure_date must be in YYYY-MM-DD format");
  }

  @Test
  void handleRequestShouldStoreAllItemStatuses() throws Exception {
    // arrange
    fakeClock.setTime(Instant.ofEpochSecond(1700000000));
    var requestBody =
        """
        {
          "name": "Trip with statuses",
          "destination": "Berlin",
          "departure_date": "2026-02-01",
          "return_date": "2026-02-10",
          "items": [
            {
              "name": "passport",
              "category": "travel",
              "quantity": 1,
              "tags": [],
              "status": "unpacked"
            },
            {
              "name": "toothbrush",
              "category": "toiletries",
              "quantity": 1,
              "tags": [],
              "status": "packed"
            },
            {
              "name": "snacks",
              "category": "misc",
              "quantity": 1,
              "tags": [],
              "status": "pack-just-in-time"
            }
          ]
        }
        """;

    var authHeader =
        "Basic "
            + Base64.getEncoder().encodeToString("bob:password".getBytes(StandardCharsets.UTF_8));
    var event =
        APIGatewayV2HTTPEvent.builder()
            .withHeaders(Map.of("Authorization", authHeader))
            .withBody(requestBody)
            .build();

    // act
    var response = createTripHandler.handleRequest(event, null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(201);

    var items = packingListTable.scan().items().stream().toList();
    assertThat(items).hasSize(1);

    var storedItem = items.get(0);
    var tripItems = storedItem.getItems();
    assertThat(tripItems).hasSize(3);

    var statuses = tripItems.stream().map(TripItem::getStatus).toList();
    assertThat(statuses)
        .containsExactlyInAnyOrder(
            TripItemStatus.UNPACKED, TripItemStatus.PACKED, TripItemStatus.PACK_JUST_IN_TIME);
  }
}
