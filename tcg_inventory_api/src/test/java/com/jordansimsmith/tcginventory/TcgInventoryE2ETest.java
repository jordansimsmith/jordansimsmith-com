package com.jordansimsmith.tcginventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jordansimsmith.dynamodb.DynamoDbUtils;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
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

  private static final String HIT_SCRYFALL_ID = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";
  private static final String HIT_SKU_ID = HIT_SCRYFALL_ID + "#normal#NM";

  private static final String CSV_BODY =
      "Name,Set code,Set name,Collector number,Foil,Quantity,Scryfall ID,Condition,Language\n"
          + "Test Hit,DOM,Dominaria,1,normal,2,"
          + HIT_SCRYFALL_ID
          + ",near_mint,en\n"
          + "Test Card B,DOM,Dominaria,169,normal,1,a1b2c3d4-e5f6-7890-abcd-ef1234567890,"
          + "near_mint,en\n";

  private static final String CHEAP_CSV_BODY =
      "Name,Set code,Set name,Collector number,Foil,Quantity,Scryfall ID,Condition,Language\n"
          + "Test Card B,DOM,Dominaria,169,normal,1,a1b2c3d4-e5f6-7890-abcd-ef1234567890,"
          + "near_mint,en\n";

  private static final byte[] FRONT_JPEG =
      new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xD9};
  private static final byte[] BACK_JPEG =
      new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, (byte) 0x00};
  private static final byte[] UNIT_TWO_JPEG =
      new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE1, (byte) 0x00};

  private static final String STUB_IMAGE_1 = "https://listing-img.fetchtcg.com/stub/listing/1.jpg";
  private static final String STUB_IMAGE_2 = "https://listing-img.fetchtcg.com/stub/listing/2.jpg";
  private static final String STUB_IMAGE_3 = "https://listing-img.fetchtcg.com/stub/listing/3.jpg";

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

    // act - upload csv (two hit copies + one cheap card)
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

    // act - update cheap row 1 condition from NM to LP, then delete it
    var updateRowResponse = put("/imports/" + importId + "/rows/1", "{\"condition\":\"LP\"}");
    assertThat(updateRowResponse.statusCode()).isEqualTo(200);
    var updatedRow = objectMapper.readTree(updateRowResponse.body());
    assertThat(updatedRow.get("condition").asText()).isEqualTo("LP");

    var deleteRowResponse = delete("/imports/" + importId + "/rows/1");
    assertThat(deleteRowResponse.statusCode()).isEqualTo(204);

    // assert - two hit rows remain, both flagged for photos
    var importDetail = get("/imports/" + importId);
    assertThat(importDetail.statusCode()).isEqualTo(200);
    var importDetailBody = objectMapper.readTree(importDetail.body());
    assertThat(importDetailBody.get("status").asText()).isEqualTo("review");
    var rows = importDetailBody.get("rows");
    assertThat(rows).hasSize(2);
    assertThat(rows.get(0).get("decision").asText()).isEqualTo("keep");
    assertThat(rows.get(0).get("needs_photos").asBoolean()).isTrue();
    assertThat(rows.get(1).get("decision").asText()).isEqualTo("keep");
    assertThat(rows.get(1).get("needs_photos").asBoolean()).isTrue();
    var firstHitPosition = rows.get(0).get("position").asInt();
    var secondHitPosition = rows.get(1).get("position").asInt();

    // act - confirm is blocked while flagged rows are photo-less
    var gatedConfirm = post("/imports/" + importId + "/confirm");
    assertThat(gatedConfirm.statusCode()).isEqualTo(409);
    assertThat(objectMapper.readTree(gatedConfirm.body()).get("message").asText())
        .isEqualTo("2 rows need photos before confirm");

    // act - photograph each remaining hit row separately
    assertThat(
            postJpeg("/imports/" + importId + "/rows/" + firstHitPosition + "/photos", FRONT_JPEG)
                .statusCode())
        .isEqualTo(204);
    assertThat(
            postJpeg("/imports/" + importId + "/rows/" + firstHitPosition + "/photos", BACK_JPEG)
                .statusCode())
        .isEqualTo(204);
    assertThat(
            postJpeg(
                    "/imports/" + importId + "/rows/" + secondHitPosition + "/photos",
                    UNIT_TWO_JPEG)
                .statusCode())
        .isEqualTo(204);

    var photographedImport = get("/imports/" + importId);
    assertThat(photographedImport.statusCode()).isEqualTo(200);
    var photographedRows = objectMapper.readTree(photographedImport.body()).get("rows");
    assertThat(photographedRows.get(0).get("needs_photos").asBoolean()).isFalse();
    assertThat(photographedRows.get(0).get("photos")).hasSize(2);
    assertThat(photographedRows.get(0).get("photos").get(0).get("url").asText()).isNotBlank();
    assertThat(photographedRows.get(1).get("needs_photos").asBoolean()).isFalse();
    assertThat(photographedRows.get(1).get("photos")).hasSize(1);
    assertThat(photographedRows.get(1).get("photos").get(0).get("url").asText()).isNotBlank();

    // act - confirm import
    var confirmResponse = post("/imports/" + importId + "/confirm");
    assertThat(confirmResponse.statusCode()).isEqualTo(200);
    var confirmBody = objectMapper.readTree(confirmResponse.body());
    assertThat(confirmBody.get("status").asText()).isEqualTo("confirmed");
    assertThat(confirmBody.get("unit_count").asInt()).isEqualTo(2);

    // assert - one sku with both units carrying photos
    var skusResponse = get("/skus");
    assertThat(skusResponse.statusCode()).isEqualTo(200);
    var skusBody = objectMapper.readTree(skusResponse.body());
    assertThat(skusBody.get("skus")).hasSize(1);

    var skuResponse = get("/skus/" + URLEncoder.encode(HIT_SKU_ID, StandardCharsets.UTF_8));
    assertThat(skuResponse.statusCode()).isEqualTo(200);
    var skuBody = objectMapper.readTree(skuResponse.body());
    assertThat(skuBody.get("units")).hasSize(2);
    assertThat(skuBody.get("units").get(0).get("photos")).hasSize(2);
    assertThat(skuBody.get("units").get(1).get("photos")).hasSize(1);

    // act - publish run 1: stub returns empty offers, listing phase creates listings
    triggerPublishAndWait();

    var firstListing = getStubListing();
    assertThat(firstListing.get("frontImage").asText()).isEqualTo(STUB_IMAGE_1);
    assertThat(firstListing.get("additionalImages")).hasSize(1);
    assertThat(firstListing.get("additionalImages").get(0).get("url").asText())
        .isEqualTo(STUB_IMAGE_2);

    // act - publish run 2: stub returns offer with listing 900001, order created directly as
    // to_pick; reserved first unit so listing swaps to the next unit's photos
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

    var swappedListing = getStubListing();
    assertThat(swappedListing.get("frontImage").asText()).isEqualTo(STUB_IMAGE_3);
    assertThat(swappedListing.get("additionalImages")).isEmpty();

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

    // act - trigger report generation and wait for completion
    triggerReportAndWait();

    // assert - verify the complete report shape
    var reportResponse = get("/reports");
    assertThat(reportResponse.statusCode()).isEqualTo(200);
    var reportBody = objectMapper.readTree(reportResponse.body());

    // assert - freshness (just generated, no mutations since)
    assertThat(reportBody.get("generated_at").asLong()).isGreaterThan(0);
    assertThat(reportBody.get("stale").asBoolean()).isFalse();

    // assert - generation status
    var generation = reportBody.get("generation");
    assertThat(generation.get("status").asText()).isEqualTo("succeeded");
    assertThat(generation.get("started_at").asLong()).isGreaterThan(0);
    assertThat(generation.get("finished_at").asLong()).isGreaterThan(0);

    // assert - report totals
    var report = reportBody.get("report");
    assertThat(report).isNotNull();
    var totals = report.get("totals");
    assertThat(totals).isNotNull();
    assertThat(totals.get("inventory_value")).isNotNull();
    assertThat(totals.get("in_stock_units").asInt()).isGreaterThanOrEqualTo(0);
    assertThat(totals.get("sku_count").asInt()).isGreaterThan(0);
    assertThat(totals.get("reserved_units").asInt()).isGreaterThanOrEqualTo(0);
    assertThat(totals.get("sold_units").asInt()).isGreaterThanOrEqualTo(0);
    assertThat(totals.get("revenue_to_date")).isNotNull();
    assertThat(totals.get("unpriced_units").asInt()).isGreaterThanOrEqualTo(0);

    // assert - top sets
    var topSets = report.get("top_sets");
    assertThat(topSets).isNotNull();
    assertThat(topSets.isArray()).isTrue();
    assertThat(topSets).isNotEmpty();
    for (var entry : topSets) {
      assertThat(entry.get("set_code").asText()).isNotEmpty();
      assertThat(entry.get("set_name").asText()).isNotEmpty();
      assertThat(entry.get("in_stock_units").asInt()).isGreaterThanOrEqualTo(0);
    }

    // assert - price buckets (always exactly 6 entries)
    var priceBuckets = report.get("price_buckets");
    assertThat(priceBuckets).isNotNull();
    assertThat(priceBuckets).hasSize(6);
    for (var bucket : priceBuckets) {
      assertThat(bucket.get("label").asText()).isNotEmpty();
      assertThat(bucket.get("in_stock_units").asInt()).isGreaterThanOrEqualTo(0);
    }

    // assert - top hits
    var topHits = report.get("top_hits");
    assertThat(topHits).isNotNull();
    assertThat(topHits.isArray()).isTrue();
    assertThat(topHits).isNotEmpty();
    for (var entry : topHits) {
      assertThat(entry.get("sku_id").asText()).isNotEmpty();
      assertThat(entry.get("name").asText()).isNotEmpty();
      assertThat(entry.get("set_code").asText()).isNotEmpty();
      assertThat(entry.get("collector_number").asText()).isNotEmpty();
      assertThat(entry.get("finish").asText()).isNotEmpty();
      assertThat(entry.get("condition").asText()).isNotEmpty();
      assertThat(entry.get("price").asText()).isNotEmpty();
      assertThat(entry.get("in_stock_units").asInt()).isGreaterThan(0);
    }

    // assert - aging bands (always exactly 4 entries)
    var agingBands = report.get("aging_bands");
    assertThat(agingBands).isNotNull();
    assertThat(agingBands).hasSize(4);
    for (var band : agingBands) {
      assertThat(band.get("label").asText()).isNotEmpty();
      assertThat(band.get("in_stock_units").asInt()).isGreaterThanOrEqualTo(0);
    }

    // assert - revenue by month (paid order exists from the flow)
    var revenueByMonth = report.get("revenue_by_month");
    assertThat(revenueByMonth).isNotNull();
    assertThat(revenueByMonth.isArray()).isTrue();
    assertThat(revenueByMonth).isNotEmpty();
    for (var entry : revenueByMonth) {
      assertThat(entry.get("month").asText()).matches("\\d{4}-\\d{2}");
      assertThat(entry.get("revenue").asText()).isNotEmpty();
      assertThat(entry.get("order_count").asInt()).isGreaterThan(0);
    }

    // assert - intake vs sales by week (intake happened during imports)
    var intakeVsSales = report.get("intake_vs_sales_by_week");
    assertThat(intakeVsSales).isNotNull();
    assertThat(intakeVsSales.isArray()).isTrue();
    assertThat(intakeVsSales).isNotEmpty();
    for (var entry : intakeVsSales) {
      assertThat(entry.get("week_start").asText()).matches("\\d{4}-\\d{2}-\\d{2}");
      assertThat(entry.get("added_units").asInt()).isGreaterThanOrEqualTo(0);
      assertThat(entry.get("sold_units").asInt()).isGreaterThanOrEqualTo(0);
    }

    // act - upload a cheap csv to create a new audit entry via confirm
    var importResponse2 =
        httpClient.send(
            HttpRequest.newBuilder()
                .uri(URI.create(apiUrl + "/imports?filename=test2.csv"))
                .header("Authorization", AUTH_HEADER)
                .header("content-type", "text/csv")
                .POST(HttpRequest.BodyPublishers.ofString(CHEAP_CSV_BODY))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(importResponse2.statusCode()).isEqualTo(200);
    var importBody2 = objectMapper.readTree(importResponse2.body());
    var importId2 = importBody2.get("import_id").asText();

    await()
        .atMost(Duration.ofSeconds(60))
        .pollInterval(Duration.ofSeconds(1))
        .untilAsserted(
            () -> {
              var pollResponse = get("/imports/" + importId2);
              assertThat(pollResponse.statusCode()).isEqualTo(200);
              var body = objectMapper.readTree(pollResponse.body());
              assertThat(body.get("status").asText()).isEqualTo("review");
            });

    var confirmResponse2 = post("/imports/" + importId2 + "/confirm");
    assertThat(confirmResponse2.statusCode()).isEqualTo(200);

    // assert - report is now stale after mutation
    var staleReportResponse = get("/reports");
    assertThat(staleReportResponse.statusCode()).isEqualTo(200);
    var staleReportBody = objectMapper.readTree(staleReportResponse.body());
    assertThat(staleReportBody.get("stale").asBoolean()).isTrue();
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

  private HttpResponse<String> postJpeg(String path, byte[] jpeg)
      throws IOException, InterruptedException {
    return httpClient.send(
        HttpRequest.newBuilder()
            .uri(URI.create(apiUrl + path))
            .header("Authorization", AUTH_HEADER)
            .header("content-type", "image/jpeg")
            .POST(HttpRequest.BodyPublishers.ofByteArray(jpeg))
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

  private void triggerReportAndWait() throws IOException, InterruptedException {
    var response = post("/reports");
    assertThat(response.statusCode()).isEqualTo(202);

    await()
        .atMost(Duration.ofSeconds(120))
        .pollInterval(Duration.ofSeconds(1))
        .untilAsserted(
            () -> {
              var pollResponse = get("/reports");
              assertThat(pollResponse.statusCode()).isEqualTo(200);
              var body = objectMapper.readTree(pollResponse.body());
              assertThat(body.get("generation").get("status").asText()).isIn("succeeded", "failed");
            });

    var result = get("/reports");
    var resultBody = objectMapper.readTree(result.body());
    assertThat(resultBody.get("generation").get("status").asText()).isEqualTo("succeeded");
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

  @SuppressWarnings("HttpUrlsUsage")
  private JsonNode getStubListing() throws IOException, InterruptedException {
    var stubUrl =
        URI.create(
            "http://"
                + fetchTcgStubContainer.getHost()
                + ":"
                + fetchTcgStubContainer.getMappedPort(8080)
                + "/v1/manage-listings");
    var response =
        httpClient.send(
            HttpRequest.newBuilder().uri(stubUrl).GET().build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).isEqualTo(200);
    var content = objectMapper.readTree(response.body()).get("content");
    assertThat(content).hasSize(1);
    return content.get(0);
  }
}
