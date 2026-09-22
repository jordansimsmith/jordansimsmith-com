package com.jordansimsmith.tcginventory;

import static org.assertj.core.api.Assertions.assertThat;

import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jordansimsmith.dynamodb.DynamoDbContainer;
import com.jordansimsmith.dynamodb.DynamoDbUtils;
import com.jordansimsmith.s3.S3Container;
import com.jordansimsmith.time.FakeClock;
import com.jordansimsmith.ulid.FakeUlidGenerator;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import javax.annotation.Nullable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.services.s3.S3Client;

@Testcontainers
public class ScansHandlerIntegrationTest {
  @Container private static final DynamoDbContainer dynamoDbContainer = new DynamoDbContainer();
  @Container private static final S3Container s3Container = new S3Container();

  private static final byte[] JPEG_BYTES =
      new byte[] {(byte) 0xFF, (byte) 0xD8, 0x01, (byte) 0xFF, (byte) 0xD9};

  private TcgInventoryTestFactory factory;
  private FakeClock fakeClock;
  private FakeUlidGenerator fakeUlidGenerator;
  private ObjectMapper objectMapper;
  private DynamoDbTable<TcgInventoryItem> tcgInventoryTable;
  private S3Client s3Client;
  private CreateScanHandler createScanHandler;
  private FindScansHandler findScansHandler;
  private GetScanHandler getScanHandler;

  @BeforeAll
  static void setUpBeforeClass() {
    var factory =
        TcgInventoryTestFactory.create(dynamoDbContainer.getEndpoint(), s3Container.getEndpoint());
    DynamoDbUtils.createTable(factory.dynamoDbClient(), factory.tcgInventoryTable());
    factory.s3Client().createBucket(request -> request.bucket(ScanImages.BUCKET));
  }

  @BeforeEach
  void setUp() {
    factory =
        TcgInventoryTestFactory.create(dynamoDbContainer.getEndpoint(), s3Container.getEndpoint());
    fakeClock = factory.fakeClock();
    fakeUlidGenerator = factory.fakeUlidGenerator();
    objectMapper = factory.objectMapper();
    tcgInventoryTable = factory.tcgInventoryTable();
    s3Client = factory.s3Client();
    for (var object :
        s3Client.listObjectsV2(request -> request.bucket(ScanImages.BUCKET)).contents()) {
      s3Client.deleteObject(request -> request.bucket(ScanImages.BUCKET).key(object.key()));
    }

    DynamoDbUtils.reset(factory.dynamoDbClient());
    fakeUlidGenerator.reset();
    createScanHandler = new CreateScanHandler(factory);
    findScansHandler = new FindScansHandler(factory);
    getScanHandler = new GetScanHandler(factory);
  }

  @Test
  void createScanShouldSortAsciiFilenamesLexicallyAndPersistRows() throws Exception {
    // arrange
    fakeClock.setTime(Instant.ofEpochSecond(1700000000));
    var request =
        "{\"condition\":\"LP\",\"finish\":\"foil\",\"files\":["
            + "{\"filename\":\"2.jpg\",\"size_bytes\":100},"
            + "{\"filename\":\"10.jpg\",\"size_bytes\":200},"
            + "{\"filename\":\"1.jpg\",\"size_bytes\":300}]}";

    // act
    var response = createScanHandler.handleRequest(buildEventWithBody("jordan", request), null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(201);
    var body = objectMapper.readTree(response.getBody());
    var scanId = body.get("scan_id").asText();
    assertThat(body).hasSize(2);
    assertThat(body.has("status")).isFalse();
    assertThat(body.has("condition")).isFalse();
    assertThat(body.has("finish")).isFalse();
    assertThat(body.has("row_count")).isFalse();
    assertThat(body.has("processed_count")).isFalse();

    var rows = body.get("rows");
    assertThat(rows).hasSize(3);
    assertThat(rows.get(0).get("filename").asText()).isEqualTo("1.jpg");
    assertThat(rows.get(1).get("filename").asText()).isEqualTo("10.jpg");
    assertThat(rows.get(2).get("filename").asText()).isEqualTo("2.jpg");
    assertThat(rows.get(0).get("scan_position").asInt()).isEqualTo(1);
    assertThat(rows.get(1).get("scan_position").asInt()).isEqualTo(2);
    assertThat(rows.get(2).get("scan_position").asInt()).isEqualTo(3);
    for (var row : rows) {
      assertThat(row.has("status")).isFalse();
      assertThat(row.has("needs_review")).isFalse();
      assertThat(row.has("suggestions")).isFalse();
      assertThat(row.has("source_url")).isFalse();
      assertThat(row.has("error")).isFalse();
      assertThat(row.get("uploaded").asBoolean()).isFalse();
      assertThat(row.get("upload_url").asText()).isNotBlank();
      assertThat(row.get("upload_headers").get("Content-Type").asText()).isEqualTo("image/jpeg");
      assertThat(row.get("upload_headers").get("If-None-Match").asText()).isEqualTo("*");
    }

    var scanItem =
        tcgInventoryTable.getItem(
            Key.builder()
                .partitionValue(TcgInventoryItem.formatUserPk("jordan"))
                .sortValue(TcgInventoryItem.formatScanSk(scanId))
                .build());
    assertThat(scanItem).isNotNull();
    var firstRow =
        tcgInventoryTable.getItem(
            Key.builder()
                .partitionValue(TcgInventoryItem.formatScanRowPk("jordan", scanId))
                .sortValue(TcgInventoryItem.formatScanRowSk(1))
                .build());
    assertThat(firstRow.getS3Key()).isEqualTo("users/jordan/scans/%s/000001.jpg".formatted(scanId));
    assertThat(scanItem.getUpdatedAt()).isEqualTo(Instant.ofEpochSecond(1700000000));

    var reread =
        new GetScanHandler(factory)
            .handleRequest(buildEventWithPath("jordan", Map.of("scan_id", scanId)), null);
    assertThat(reread.getStatusCode()).isEqualTo(200);
    assertThat(objectMapper.readTree(reread.getBody()).get("rows").get(0).get("filename").asText())
        .isEqualTo("1.jpg");
    assertThat(
            objectMapper.readTree(reread.getBody()).get("rows").get(0).get("upload_url").isNull())
        .isTrue();
  }

  @Test
  void scanDetailShouldVerifyPresignedUploadAndServeFreshGetUrl()
      throws IOException, InterruptedException {
    // arrange
    var response =
        createScanHandler.handleRequest(
            buildEventWithBody(
                "jordan",
                "{\"condition\":\"NM\",\"finish\":\"normal\",\"files\":[{\"filename\":\"001.jpg\",\"size_bytes\":5}]}"),
            null);
    var body = objectMapper.readTree(response.getBody());
    var row = body.get("rows").get(0);
    var headers = row.get("upload_headers");
    var httpClient = HttpClient.newHttpClient();

    // act
    var putResponse =
        httpClient.send(
            HttpRequest.newBuilder(URI.create(row.get("upload_url").asText()))
                .header("Content-Type", headers.get("Content-Type").asText())
                .header("If-None-Match", headers.get("If-None-Match").asText())
                .PUT(HttpRequest.BodyPublishers.ofByteArray(JPEG_BYTES))
                .build(),
            HttpResponse.BodyHandlers.ofByteArray());
    var detailResponse =
        getScanHandler.handleRequest(
            buildEventWithPath("jordan", Map.of("scan_id", body.get("scan_id").asText())), null);
    var detailRow = objectMapper.readTree(detailResponse.getBody()).get("rows").get(0);
    var getResponse =
        httpClient.send(
            HttpRequest.newBuilder(URI.create(detailRow.get("source_url").asText())).GET().build(),
            HttpResponse.BodyHandlers.ofByteArray());

    // assert
    assertThat(putResponse.statusCode()).isEqualTo(200);
    assertThat(detailRow.get("uploaded").asBoolean()).isTrue();
    assertThat(detailRow.get("source_url").asText()).isNotBlank();
    assertThat(detailRow.get("upload_url").isNull()).isTrue();
    assertThat(detailRow.get("upload_headers").isNull()).isTrue();
    assertThat(getResponse.statusCode()).isEqualTo(200);
    assertThat(getResponse.body()).isEqualTo(JPEG_BYTES);
  }

  @Test
  void scanDetailShouldRejectReplacementAndInvalidHeadMetadata()
      throws IOException, InterruptedException {
    // arrange
    var response =
        createScanHandler.handleRequest(
            buildEventWithBody(
                "jordan",
                "{\"condition\":\"NM\",\"finish\":\"normal\",\"files\":[{\"filename\":\"001.jpg\",\"size_bytes\":5}]}"),
            null);
    var body = objectMapper.readTree(response.getBody());
    var row = body.get("rows").get(0);
    var headers = row.get("upload_headers");
    var uploadRequest =
        HttpRequest.newBuilder(URI.create(row.get("upload_url").asText()))
            .header("Content-Type", headers.get("Content-Type").asText())
            .header("If-None-Match", headers.get("If-None-Match").asText())
            .PUT(HttpRequest.BodyPublishers.ofByteArray(JPEG_BYTES))
            .build();
    var httpClient = HttpClient.newHttpClient();

    // act
    var firstPut = httpClient.send(uploadRequest, HttpResponse.BodyHandlers.discarding());
    var replacementPut =
        httpClient.send(
            HttpRequest.newBuilder(URI.create(row.get("upload_url").asText()))
                .header("Content-Type", headers.get("Content-Type").asText())
                .header("If-None-Match", headers.get("If-None-Match").asText())
                .PUT(HttpRequest.BodyPublishers.ofByteArray(new byte[] {1, 2, 3, 4, 5}))
                .build(),
            HttpResponse.BodyHandlers.discarding());
    var invalidScanId = createScan("jordan", "002.jpg");
    s3Client.putObject(
        request ->
            request
                .bucket(ScanImages.BUCKET)
                .key("users/jordan/scans/%s/000001.jpg".formatted(invalidScanId))
                .contentType("image/png"),
        RequestBody.fromBytes(new byte[] {1}));
    var invalidDetail =
        getScanHandler.handleRequest(
            buildEventWithPath("jordan", Map.of("scan_id", invalidScanId)), null);

    // assert
    assertThat(firstPut.statusCode()).isEqualTo(200);
    assertThat(replacementPut.statusCode()).isEqualTo(412);
    var invalidRow = objectMapper.readTree(invalidDetail.getBody()).get("rows").get(0);
    assertThat(invalidRow.get("uploaded").asBoolean()).isFalse();
    assertThat(invalidRow.get("source_url").isNull()).isTrue();
    assertThat(invalidRow.get("upload_url").isNull()).isTrue();

    // arrange
    var wrongSizeScanId = createScan("jordan", "003.jpg", 2);
    s3Client.putObject(
        request ->
            request
                .bucket(ScanImages.BUCKET)
                .key("users/jordan/scans/%s/000001.jpg".formatted(wrongSizeScanId))
                .contentType("image/jpeg"),
        RequestBody.fromBytes(new byte[] {1}));

    // act
    var wrongSizeDetail =
        getScanHandler.handleRequest(
            buildEventWithPath("jordan", Map.of("scan_id", wrongSizeScanId)), null);

    // assert
    assertThat(
            objectMapper
                .readTree(wrongSizeDetail.getBody())
                .get("rows")
                .get(0)
                .get("uploaded")
                .asBoolean())
        .isFalse();
  }

  @Test
  void createScanShouldRejectNonAsciiFilenames() throws Exception {
    // arrange
    var request =
        "{\"condition\":\"NM\",\"finish\":\"normal\",\"files\":["
            + "{\"filename\":\"café.jpg\",\"size_bytes\":1}]}";

    // act
    var response = createScanHandler.handleRequest(buildEventWithBody("jordan", request), null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(400);
    assertThat(response.getBody()).contains("ASCII");
    var scans =
        objectMapper.readTree(findScansHandler.handleRequest(buildEvent("jordan"), null).getBody());
    assertThat(scans.get("scans")).isEmpty();
  }

  @Test
  void createScanShouldAcceptEveryConditionAndFinish() throws Exception {
    // arrange
    var conditions = List.of("NM", "LP", "MP", "HP", "DMG");
    var finishes = List.of("normal", "foil", "etched");

    // act
    for (var condition : conditions) {
      for (var finish : finishes) {
        var body =
            "{\"condition\":\""
                + condition
                + "\",\"finish\":\""
                + finish
                + "\",\"files\":[{\"filename\":\""
                + condition
                + finish
                + ".jpg\",\"size_bytes\":1}]}";
        var response = createScanHandler.handleRequest(buildEventWithBody("jordan", body), null);
        assertThat(response.getStatusCode()).isEqualTo(201);
      }
    }

    // assert
    var response = findScansHandler.handleRequest(buildEvent("jordan"), null);
    assertThat(response.getStatusCode()).isEqualTo(200);
    assertThat(objectMapper.readTree(response.getBody()).get("scans")).hasSize(15);
  }

  @Test
  void createScanShouldRejectInvalidBatchesWithoutWritingItems() throws Exception {
    // arrange
    var tooManyFiles =
        IntStream.range(0, 201)
            .mapToObj(index -> "{\"filename\":\"" + index + ".jpg\",\"size_bytes\":1}")
            .collect(Collectors.joining(","));
    var invalidBodies =
        List.of(
            "{bad",
            "{\"condition\":\"ZZ\",\"finish\":\"normal\",\"files\":[{\"filename\":\"1.jpg\",\"size_bytes\":1}]}",
            "{\"condition\":\"NM\",\"finish\":\"glitter\",\"files\":[{\"filename\":\"1.jpg\",\"size_bytes\":1}]}",
            "{\"condition\":\"NM\",\"finish\":\"normal\",\"files\":[]}",
            "{\"condition\":\"NM\",\"finish\":\"normal\",\"files\":[{\"filename\":\"1.jpg\",\"size_bytes\":0}]}",
            "{\"condition\":\"NM\",\"finish\":\"normal\",\"files\":[{\"filename\":\"1.jpg\",\"size_bytes\":1.5}]}",
            "{\"condition\":\"NM\",\"finish\":\"normal\",\"files\":[{\"filename\":\"1.jpg\",\"size_bytes\":1048577}]}",
            "{\"condition\":\"NM\",\"finish\":\"normal\",\"files\":[{\"filename\":\"1.png\",\"size_bytes\":1}]}",
            "{\"condition\":\"NM\",\"finish\":\"normal\",\"files\":[{\"filename\":\"1.jpg\",\"size_bytes\":1},{\"filename\":\"1.jpg\",\"size_bytes\":1}]}",
            "{\"condition\":\"NM\",\"finish\":\"normal\",\"files\":[" + tooManyFiles + "]}");

    // act
    for (var body : invalidBodies) {
      var response = createScanHandler.handleRequest(buildEventWithBody("jordan", body), null);

      // assert
      assertThat(response.getStatusCode()).isEqualTo(400);
    }
    var emptyBodyResponse =
        createScanHandler.handleRequest(buildEventWithBody("jordan", null), null);

    // assert
    assertThat(emptyBodyResponse.getStatusCode()).isEqualTo(400);
    var scans =
        objectMapper.readTree(findScansHandler.handleRequest(buildEvent("jordan"), null).getBody());
    assertThat(scans.get("scans")).isEmpty();
  }

  @Test
  void findScansShouldReturnNewestFirstAndSupportContinuation() throws Exception {
    // arrange
    createScan("jordan", "first.jpg");
    createScan("jordan", "second.jpg");
    createScan("jordan", "third.jpg");

    // act
    var firstResponse =
        findScansHandler.handleRequest(buildEventWithQuery("jordan", Map.of("limit", "2")), null);

    // assert
    assertThat(firstResponse.getStatusCode()).isEqualTo(200);
    var firstPage = objectMapper.readTree(firstResponse.getBody());
    assertThat(firstPage.get("scans").get(0).get("scan_id").asText())
        .isEqualTo("FAKE_ULID_0000000003");
    assertThat(firstPage.get("scans").get(1).get("scan_id").asText())
        .isEqualTo("FAKE_ULID_0000000002");
    var continuation = firstPage.get("next_continuation").asText();

    // act
    var secondResponse =
        findScansHandler.handleRequest(
            buildEventWithQuery("jordan", Map.of("continuation", continuation, "limit", "2")),
            null);

    // assert
    assertThat(secondResponse.getStatusCode()).isEqualTo(200);
    var secondPage = objectMapper.readTree(secondResponse.getBody());
    assertThat(secondPage.get("scans")).hasSize(1);
    assertThat(secondPage.get("scans").get(0).get("scan_id").asText())
        .isEqualTo("FAKE_ULID_0000000001");
    assertThat(secondPage.get("next_continuation").isNull()).isTrue();
  }

  @Test
  void scanReadsShouldBeScopedToAuthenticatedUser() throws Exception {
    // arrange
    var createResponse =
        createScanHandler.handleRequest(buildEventWithBody("alice", validBody()), null);
    var scanId = objectMapper.readTree(createResponse.getBody()).get("scan_id").asText();

    // act
    var listResponse = findScansHandler.handleRequest(buildEvent("bob"), null);
    var detailResponse =
        getScanHandler.handleRequest(buildEventWithPath("bob", Map.of("scan_id", scanId)), null);
    var unknownResponse =
        getScanHandler.handleRequest(
            buildEventWithPath("alice", Map.of("scan_id", "missing")), null);

    // assert
    assertThat(listResponse.getStatusCode()).isEqualTo(200);
    assertThat(objectMapper.readTree(listResponse.getBody()).get("scans")).isEmpty();
    assertThat(detailResponse.getStatusCode()).isEqualTo(404);
    assertThat(detailResponse.getBody()).contains("Not Found");
    assertThat(unknownResponse.getStatusCode()).isEqualTo(404);
  }

  private String createScan(String user, String filename) throws IOException {
    return createScan(user, filename, 1);
  }

  private String createScan(String user, String filename, int sizeBytes) throws IOException {
    var response =
        createScanHandler.handleRequest(
            buildEventWithBody(
                user,
                "{\"condition\":\"NM\",\"finish\":\"normal\",\"files\":[{\"filename\":\""
                    + filename
                    + "\",\"size_bytes\":"
                    + sizeBytes
                    + "}]}"),
            null);
    assertThat(response.getStatusCode()).isEqualTo(201);
    return objectMapper.readTree(response.getBody()).get("scan_id").asText();
  }

  private String validBody() {
    return "{\"condition\":\"NM\",\"finish\":\"normal\",\"files\":[{\"filename\":\"001.jpg\",\"size_bytes\":1}]}";
  }

  private APIGatewayV2HTTPEvent buildEvent(String user) {
    return buildEvent(user, Map.of(), Map.of());
  }

  private APIGatewayV2HTTPEvent buildEventWithQuery(String user, Map<String, String> queryParams) {
    return buildEvent(user, Map.of(), queryParams);
  }

  private APIGatewayV2HTTPEvent buildEventWithPath(String user, Map<String, String> pathParams) {
    return buildEvent(user, pathParams, Map.of());
  }

  private APIGatewayV2HTTPEvent buildEventWithBody(String user, @Nullable String body) {
    var authHeader =
        "Basic "
            + Base64.getEncoder()
                .encodeToString((user + ":password").getBytes(StandardCharsets.UTF_8));
    return APIGatewayV2HTTPEvent.builder()
        .withHeaders(Map.of("Authorization", authHeader))
        .withBody(body)
        .build();
  }

  private APIGatewayV2HTTPEvent buildEvent(
      String user, Map<String, String> pathParams, Map<String, String> queryParams) {
    var authHeader =
        "Basic "
            + Base64.getEncoder()
                .encodeToString((user + ":password").getBytes(StandardCharsets.UTF_8));
    return APIGatewayV2HTTPEvent.builder()
        .withHeaders(Map.of("Authorization", authHeader))
        .withPathParameters(pathParams)
        .withQueryStringParameters(queryParams)
        .build();
  }
}
