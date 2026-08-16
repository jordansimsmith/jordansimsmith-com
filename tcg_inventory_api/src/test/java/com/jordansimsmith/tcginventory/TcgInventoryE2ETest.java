package com.jordansimsmith.tcginventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jordansimsmith.dynamodb.DynamoDbUtils;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.Network;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

public class TcgInventoryE2ETest {
  private static final String AUTH_HEADER =
      "Basic "
          + Base64.getEncoder().encodeToString("jordan:password".getBytes(StandardCharsets.UTF_8));

  private static final String CSV_BODY =
      "Name,Set code,Set name,Collector number,Foil,Quantity,Scryfall ID,Condition,Language\n"
          + "Test Card A,DOM,Dominaria,168,normal,1,f0a51425-d796-48b8-b68c-bc21fb465c81,"
          + "near_mint,en\n"
          + "Test Card B,DOM,Dominaria,169,normal,1,a1b2c3d4-e5f6-7890-abcd-ef1234567890,"
          + "near_mint,en\n"
          + "Test Card C,DOM,Dominaria,170,normal,1,11111111-2222-3333-4444-555555555555,"
          + "near_mint,en\n";

  private static final Network NETWORK = Network.newNetwork();

  private static final FetchTcgStubContainer fetchTcgStubContainer =
      new FetchTcgStubContainer().withNetwork(NETWORK);

  private static final FirebaseStubContainer firebaseStubContainer =
      new FirebaseStubContainer().withNetwork(NETWORK);

  private static final TcgInventoryContainer tcgInventoryContainer =
      new TcgInventoryContainer()
          .withNetwork(NETWORK)
          .withEnv("FETCHTCG_BASE_URL", fetchTcgStubContainer.getEndpoint().toString())
          .withEnv("FIREBASE_TOKEN_URL", firebaseStubContainer.getEndpoint() + "/v1/token");

  private HttpClient httpClient;
  private ObjectMapper objectMapper;
  private URI apiUrl;

  @BeforeAll
  static void setUpBeforeClass() {
    fetchTcgStubContainer.start();
    firebaseStubContainer.start();
    tcgInventoryContainer.start();
  }

  @AfterAll
  static void tearDownAfterClass() {
    tcgInventoryContainer.stop();
    firebaseStubContainer.stop();
    fetchTcgStubContainer.stop();
    NETWORK.close();
  }

  @BeforeEach
  void setUp() {
    var dynamoDbClient =
        DynamoDbClient.builder().endpointOverride(tcgInventoryContainer.getLocalstackUrl()).build();
    DynamoDbUtils.reset(dynamoDbClient);

    httpClient = HttpClient.newHttpClient();
    objectMapper = new ObjectMapper();
    apiUrl = tcgInventoryContainer.getApiUrl();
  }

  @Test
  void fullLoopImportToOrderConfirm() throws IOException, InterruptedException {
    // arrange - store fake refresh token
    var settingsResponse = patch("/settings", "{\"refresh_token\":\"fake-token\"}");
    assertThat(settingsResponse.statusCode()).isEqualTo(200);

    // act - upload csv
    var importResponse =
        httpClient.send(
            HttpRequest.newBuilder()
                .uri(URI.create(apiUrl + "/imports?filename=test.csv"))
                .header("Authorization", AUTH_HEADER)
                .header("content-type", "text/csv")
                .POST(HttpRequest.BodyPublishers.ofString(CSV_BODY))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(importResponse.statusCode()).isEqualTo(200);
    var importBody = objectMapper.readTree(importResponse.body());
    var importId = importBody.get("import_id").asText();
    assertThat(importBody.get("status").asText()).isEqualTo("appraising");
    assertThat(importBody.get("row_count").asInt()).isEqualTo(3);

    // act - poll until appraisal completes
    await()
        .atMost(Duration.ofSeconds(60))
        .pollInterval(Duration.ofSeconds(1))
        .untilAsserted(
            () -> {
              var pollResponse = get("/imports/" + importId);
              assertThat(pollResponse.statusCode()).isEqualTo(200);
              var body = objectMapper.readTree(pollResponse.body());
              assertThat(body.get("status").asText()).isEqualTo("review");
            });

    // act - update row 1 condition from NM to LP
    var updateRowResponse = put("/imports/" + importId + "/rows/1", "{\"condition\":\"LP\"}");
    assertThat(updateRowResponse.statusCode()).isEqualTo(200);
    var updatedRow = objectMapper.readTree(updateRowResponse.body());
    assertThat(updatedRow.get("condition").asText()).isEqualTo("LP");

    // act - delete row 3
    var deleteRowResponse = delete("/imports/" + importId + "/rows/3");
    assertThat(deleteRowResponse.statusCode()).isEqualTo(204);

    // assert - verify import still in review with modified rows
    var importDetail = get("/imports/" + importId);
    assertThat(importDetail.statusCode()).isEqualTo(200);
    var importDetailBody = objectMapper.readTree(importDetail.body());
    assertThat(importDetailBody.get("status").asText()).isEqualTo("review");
    var rows = importDetailBody.get("rows");
    assertThat(rows).hasSize(2);
    assertThat(rows.get(0).get("decision").asText()).isEqualTo("keep");

    // act - confirm import
    var confirmResponse = post("/imports/" + importId + "/confirm");
    assertThat(confirmResponse.statusCode()).isEqualTo(200);
    var confirmBody = objectMapper.readTree(confirmResponse.body());
    assertThat(confirmBody.get("status").asText()).isEqualTo("confirmed");
    assertThat(confirmBody.get("unit_count").asInt()).isEqualTo(2);

    // assert - verify skus exist
    var skusResponse = get("/skus");
    assertThat(skusResponse.statusCode()).isEqualTo(200);
    var skusBody = objectMapper.readTree(skusResponse.body());
    assertThat(skusBody.get("skus").size()).isGreaterThanOrEqualTo(2);

    // act - publish run 1: stub returns empty offers, listing phase creates listings
    triggerPublishAndWait();

    // act - publish run 2: stub returns offer with listing 900001, order created directly as
    // to_pick
    triggerPublishAndWait();

    // act - list orders
    var ordersResponse = get("/orders");
    assertThat(ordersResponse.statusCode()).isEqualTo(200);
    var ordersBody = objectMapper.readTree(ordersResponse.body());
    assertThat(ordersBody.get("orders")).hasSize(1);
    assertThat(ordersBody.get("orders").get(0).get("order_id").asText()).isEqualTo("99001");
    assertThat(ordersBody.get("orders").get(0).get("state").asText()).isEqualTo("to_pick");

    // act - get order detail (pull sheet)
    var orderDetailResponse = get("/orders/99001");
    assertThat(orderDetailResponse.statusCode()).isEqualTo(200);
    var orderDetailBody = objectMapper.readTree(orderDetailResponse.body());
    assertThat(orderDetailBody.get("order_id").asText()).isEqualTo("99001");
    assertThat(orderDetailBody.get("state").asText()).isEqualTo("to_pick");
    assertThat(orderDetailBody.get("delivery_mode").asText()).isEqualTo("PICKUP");
    var orderUnits = orderDetailBody.get("units");
    assertThat(orderUnits).hasSize(1);
    assertThat(orderUnits.get(0).get("sequence_number").asInt()).isZero();
    assertThat(orderUnits.get(0).get("location").asText()).isEqualTo("A0-0");

    // act - confirm pull
    var confirmOrderResponse = post("/orders/99001/confirm");
    assertThat(confirmOrderResponse.statusCode()).isEqualTo(200);
    var confirmOrderBody = objectMapper.readTree(confirmOrderResponse.body());
    assertThat(confirmOrderBody.get("state").asText()).isEqualTo("fulfilled");

    // assert - verify order is fulfilled
    var finalOrdersResponse = get("/orders");
    assertThat(finalOrdersResponse.statusCode()).isEqualTo(200);
    var finalOrdersBody = objectMapper.readTree(finalOrdersResponse.body());
    assertThat(finalOrdersBody.get("orders").get(0).get("state").asText()).isEqualTo("fulfilled");
  }

  private HttpResponse<String> get(String path) throws IOException, InterruptedException {
    return httpClient.send(
        HttpRequest.newBuilder()
            .uri(URI.create(apiUrl + path))
            .header("Authorization", AUTH_HEADER)
            .GET()
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> post(String path) throws IOException, InterruptedException {
    return httpClient.send(
        HttpRequest.newBuilder()
            .uri(URI.create(apiUrl + path))
            .header("Authorization", AUTH_HEADER)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(""))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> put(String path, String body)
      throws IOException, InterruptedException {
    return httpClient.send(
        HttpRequest.newBuilder()
            .uri(URI.create(apiUrl + path))
            .header("Authorization", AUTH_HEADER)
            .header("Content-Type", "application/json")
            .PUT(HttpRequest.BodyPublishers.ofString(body))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> patch(String path, String body)
      throws IOException, InterruptedException {
    return httpClient.send(
        HttpRequest.newBuilder()
            .uri(URI.create(apiUrl + path))
            .header("Authorization", AUTH_HEADER)
            .header("Content-Type", "application/json")
            .method("PATCH", HttpRequest.BodyPublishers.ofString(body))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private void triggerPublishAndWait() throws IOException, InterruptedException {
    var response = post("/publish");
    assertThat(response.statusCode()).isEqualTo(202);

    await()
        .atMost(Duration.ofSeconds(120))
        .pollInterval(Duration.ofSeconds(1))
        .untilAsserted(
            () -> {
              var pollResponse = get("/publish");
              assertThat(pollResponse.statusCode()).isEqualTo(200);
              var body = objectMapper.readTree(pollResponse.body());
              assertThat(body.get("status").asText()).isIn("succeeded", "failed");
            });

    var result = get("/publish");
    var resultBody = objectMapper.readTree(result.body());
    assertThat(resultBody.get("status").asText()).isEqualTo("succeeded");
  }

  private HttpResponse<String> delete(String path) throws IOException, InterruptedException {
    return httpClient.send(
        HttpRequest.newBuilder()
            .uri(URI.create(apiUrl + path))
            .header("Authorization", AUTH_HEADER)
            .DELETE()
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }
}
