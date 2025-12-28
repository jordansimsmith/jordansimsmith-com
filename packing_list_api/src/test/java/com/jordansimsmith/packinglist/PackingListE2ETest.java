package com.jordansimsmith.packinglist;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jordansimsmith.dynamodb.DynamoDbUtils;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

@Testcontainers
public class PackingListE2ETest {

  @Container
  private static final PackingListContainer packingListContainer = new PackingListContainer();

  private HttpClient httpClient;
  private ObjectMapper objectMapper;
  private URI apiUrl;

  @BeforeEach
  void setup() {
    var dynamoDbClient =
        DynamoDbClient.builder().endpointOverride(packingListContainer.getLocalstackUrl()).build();

    DynamoDbUtils.reset(dynamoDbClient);

    httpClient = HttpClient.newHttpClient();
    objectMapper = new ObjectMapper();
    apiUrl = packingListContainer.getApiUrl();
  }

  @Test
  void shouldStartContainer() {
    assertThat(packingListContainer.isRunning()).isTrue();
  }

  @Test
  void shouldCreateListGetUpdateAndGetTrip() throws Exception {
    var user = "testuser";

    // create trip
    var createRequest =
        new CreateTripHandler.CreateTripRequest(
            "Beach Vacation",
            "Hawaii",
            "2025-03-15",
            "2025-03-22",
            List.of(
                new Trip.Item("Sunscreen", "Toiletries", 2, List.of("essential"), "unpacked"),
                new Trip.Item("Swimsuit", "Clothing", 1, List.of(), "unpacked"),
                new Trip.Item(
                    "Passport", "Documents", 1, List.of("important"), "pack-just-in-time"),
                new Trip.Item("Sandals", "Footwear", 1, List.of(), "packed")));

    var createHttpRequest =
        HttpRequest.newBuilder()
            .uri(URI.create(apiUrl + "/trips?user=" + user))
            .header("Content-Type", "application/json")
            .POST(
                HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(createRequest)))
            .build();

    var createHttpResponse =
        httpClient.send(createHttpRequest, HttpResponse.BodyHandlers.ofString());
    assertThat(createHttpResponse.statusCode()).isEqualTo(201);

    var createResponse =
        objectMapper.readValue(
            createHttpResponse.body(), CreateTripHandler.CreateTripResponse.class);
    var tripId = createResponse.trip().tripId();
    assertThat(tripId).isNotNull();
    assertThat(createResponse.trip().name()).isEqualTo("Beach Vacation");
    assertThat(createResponse.trip().destination()).isEqualTo("Hawaii");
    assertThat(createResponse.trip().departureDate()).isEqualTo("2025-03-15");
    assertThat(createResponse.trip().returnDate()).isEqualTo("2025-03-22");
    assertThat(createResponse.trip().items()).hasSize(4);
    assertThat(createResponse.trip().createdAt()).isGreaterThan(0);
    assertThat(createResponse.trip().updatedAt()).isEqualTo(createResponse.trip().createdAt());

    // list trips
    var listHttpRequest =
        HttpRequest.newBuilder().uri(URI.create(apiUrl + "/trips?user=" + user)).GET().build();

    var listHttpResponse = httpClient.send(listHttpRequest, HttpResponse.BodyHandlers.ofString());
    assertThat(listHttpResponse.statusCode()).isEqualTo(200);

    var listResponse =
        objectMapper.readValue(listHttpResponse.body(), FindTripsHandler.FindTripsResponse.class);
    assertThat(listResponse.trips()).hasSize(1);
    assertThat(listResponse.trips().get(0).tripId()).isEqualTo(tripId);
    assertThat(listResponse.trips().get(0).name()).isEqualTo("Beach Vacation");
    assertThat(listResponse.trips().get(0).destination()).isEqualTo("Hawaii");

    // get trip details
    var getHttpRequest =
        HttpRequest.newBuilder()
            .uri(URI.create(apiUrl + "/trips/" + tripId + "?user=" + user))
            .GET()
            .build();

    var getHttpResponse = httpClient.send(getHttpRequest, HttpResponse.BodyHandlers.ofString());
    assertThat(getHttpResponse.statusCode()).isEqualTo(200);

    var getResponse =
        objectMapper.readValue(getHttpResponse.body(), GetTripHandler.GetTripResponse.class);
    assertThat(getResponse.trip().tripId()).isEqualTo(tripId);
    assertThat(getResponse.trip().name()).isEqualTo("Beach Vacation");
    assertThat(getResponse.trip().items()).hasSize(4);

    var originalCreatedAt = getResponse.trip().createdAt();

    // update trip
    var updateRequest =
        new UpdateTripHandler.UpdateTripRequest(
            tripId,
            "Beach Vacation Updated",
            "Maui",
            "2025-03-16",
            "2025-03-23",
            List.of(
                new Trip.Item("Sunscreen SPF 50", "Toiletries", 3, List.of("essential"), "packed"),
                new Trip.Item("Swimsuit", "Clothing", 2, List.of("beach"), "packed"),
                new Trip.Item("Passport", "Documents", 1, List.of("important"), "packed"),
                new Trip.Item("Beach Towel", "Beach Gear", 2, List.of(), "unpacked")));

    var updateHttpRequest =
        HttpRequest.newBuilder()
            .uri(URI.create(apiUrl + "/trips/" + tripId + "?user=" + user))
            .header("Content-Type", "application/json")
            .PUT(
                HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(updateRequest)))
            .build();

    var updateHttpResponse =
        httpClient.send(updateHttpRequest, HttpResponse.BodyHandlers.ofString());
    assertThat(updateHttpResponse.statusCode()).isEqualTo(200);

    var updateResponse =
        objectMapper.readValue(
            updateHttpResponse.body(), UpdateTripHandler.UpdateTripResponse.class);
    assertThat(updateResponse.trip().tripId()).isEqualTo(tripId);
    assertThat(updateResponse.trip().name()).isEqualTo("Beach Vacation Updated");
    assertThat(updateResponse.trip().destination()).isEqualTo("Maui");
    assertThat(updateResponse.trip().departureDate()).isEqualTo("2025-03-16");
    assertThat(updateResponse.trip().returnDate()).isEqualTo("2025-03-23");
    assertThat(updateResponse.trip().items()).hasSize(4);
    assertThat(updateResponse.trip().createdAt()).isEqualTo(originalCreatedAt);
    assertThat(updateResponse.trip().updatedAt()).isGreaterThanOrEqualTo(originalCreatedAt);

    // get trip details again to verify update persisted
    var getAfterUpdateHttpRequest =
        HttpRequest.newBuilder()
            .uri(URI.create(apiUrl + "/trips/" + tripId + "?user=" + user))
            .GET()
            .build();

    var getAfterUpdateHttpResponse =
        httpClient.send(getAfterUpdateHttpRequest, HttpResponse.BodyHandlers.ofString());
    assertThat(getAfterUpdateHttpResponse.statusCode()).isEqualTo(200);

    var getAfterUpdateResponse =
        objectMapper.readValue(
            getAfterUpdateHttpResponse.body(), GetTripHandler.GetTripResponse.class);
    assertThat(getAfterUpdateResponse.trip().tripId()).isEqualTo(tripId);
    assertThat(getAfterUpdateResponse.trip().name()).isEqualTo("Beach Vacation Updated");
    assertThat(getAfterUpdateResponse.trip().destination()).isEqualTo("Maui");
    assertThat(getAfterUpdateResponse.trip().departureDate()).isEqualTo("2025-03-16");
    assertThat(getAfterUpdateResponse.trip().returnDate()).isEqualTo("2025-03-23");
    assertThat(getAfterUpdateResponse.trip().items()).hasSize(4);
    assertThat(getAfterUpdateResponse.trip().createdAt()).isEqualTo(originalCreatedAt);

    // verify item details are correct
    var items = getAfterUpdateResponse.trip().items();
    var sunscreenItem =
        items.stream().filter(i -> i.name().contains("Sunscreen")).findFirst().orElseThrow();
    assertThat(sunscreenItem.name()).isEqualTo("Sunscreen SPF 50");
    assertThat(sunscreenItem.quantity()).isEqualTo(3);
    assertThat(sunscreenItem.status()).isEqualTo("packed");

    var beachTowelItem =
        items.stream().filter(i -> i.name().equals("Beach Towel")).findFirst().orElseThrow();
    assertThat(beachTowelItem.category()).isEqualTo("Beach Gear");
    assertThat(beachTowelItem.quantity()).isEqualTo(2);
    assertThat(beachTowelItem.status()).isEqualTo("unpacked");
  }
}
