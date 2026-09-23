package com.jordansimsmith.tcginventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jordansimsmith.dynamodb.DynamoDbContainer;
import com.jordansimsmith.dynamodb.DynamoDbUtils;
import com.jordansimsmith.queue.FakeQueueClient;
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
import java.util.ArrayList;
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
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryEnhancedRequest;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.S3Exception;

@Testcontainers
public class ScansHandlerIntegrationTest {
  @Container private static final DynamoDbContainer dynamoDbContainer = new DynamoDbContainer();
  @Container private static final S3Container s3Container = new S3Container();

  private static final byte[] JPEG_BYTES =
      new byte[] {(byte) 0xFF, (byte) 0xD8, 0x01, (byte) 0xFF, (byte) 0xD9};

  private TcgInventoryTestFactory factory;
  private FakeClock fakeClock;
  private FakeUlidGenerator fakeUlidGenerator;
  private FakeQueueClient<JobMessage> fakeJobsQueue;
  private FakeQueueClient<ScanMessage> fakeScanQueue;
  private ObjectMapper objectMapper;
  private DynamoDbTable<TcgInventoryItem> tcgInventoryTable;
  private S3Client s3Client;
  private CreateScanHandler createScanHandler;
  private FindScansHandler findScansHandler;
  private GetScanHandler getScanHandler;
  private IdentifyScanHandler identifyScanHandler;
  private ConfirmScanHandler confirmScanHandler;
  private DeleteScanRowHandler deleteScanRowHandler;
  private DeleteScanHandler deleteScanHandler;

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
    fakeJobsQueue = factory.fakeJobsQueue();
    fakeScanQueue = factory.fakeScanQueue();
    objectMapper = factory.objectMapper();
    tcgInventoryTable = factory.tcgInventoryTable();
    s3Client = factory.s3Client();
    for (var object :
        s3Client.listObjectsV2(request -> request.bucket(ScanImages.BUCKET)).contents()) {
      s3Client.deleteObject(request -> request.bucket(ScanImages.BUCKET).key(object.key()));
    }

    DynamoDbUtils.reset(factory.dynamoDbClient());
    fakeUlidGenerator.reset();
    fakeJobsQueue.reset();
    fakeScanQueue.reset();
    createScanHandler = new CreateScanHandler(factory);
    findScansHandler = new FindScansHandler(factory);
    getScanHandler = new GetScanHandler(factory);
    identifyScanHandler = new IdentifyScanHandler(factory);
    confirmScanHandler = new ConfirmScanHandler(factory);
    deleteScanRowHandler = new DeleteScanRowHandler(factory);
    deleteScanHandler = new DeleteScanHandler(factory);
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
  void identifyScanShouldVerifyUploadsTransitionOnceAndEnqueueMessage() throws Exception {
    // arrange
    fakeClock.setTime(Instant.ofEpochSecond(1700000000));
    var createResponse =
        createScanHandler.handleRequest(
            buildEventWithBody(
                "jordan",
                "{\"condition\":\"NM\",\"finish\":\"normal\",\"files\":["
                    + "{\"filename\":\"001.jpg\",\"size_bytes\":5},"
                    + "{\"filename\":\"002.jpg\",\"size_bytes\":6}]}"),
            null);
    var createBody = objectMapper.readTree(createResponse.getBody());
    var scanId = createBody.get("scan_id").asText();
    var rows = createBody.get("rows");
    s3Client.putObject(
        request ->
            request
                .bucket(ScanImages.BUCKET)
                .key("users/jordan/scans/%s/000001.jpg".formatted(scanId))
                .contentType(ScanImages.CONTENT_TYPE),
        RequestBody.fromBytes(JPEG_BYTES));
    s3Client.putObject(
        request ->
            request
                .bucket(ScanImages.BUCKET)
                .key("users/jordan/scans/%s/000002.jpg".formatted(scanId))
                .contentType(ScanImages.CONTENT_TYPE),
        RequestBody.fromBytes(new byte[] {1, 2, 3, 4, 5, 6}));
    fakeClock.setTime(Instant.ofEpochSecond(1700000100));

    // act
    var firstResponse =
        identifyScanHandler.handleRequest(
            buildEventWithPath("jordan", Map.of("scan_id", scanId)), null);
    var secondResponse =
        identifyScanHandler.handleRequest(
            buildEventWithPath("jordan", Map.of("scan_id", scanId)), null);

    // assert
    assertThat(firstResponse.getStatusCode()).isEqualTo(202);
    assertThat(objectMapper.readTree(firstResponse.getBody()))
        .isEqualTo(
            objectMapper.readTree(
                "{\"scan_id\":\"%s\",\"status\":\"identifying\"}".formatted(scanId)));
    assertThat(secondResponse.getStatusCode()).isEqualTo(202);
    assertThat(objectMapper.readTree(secondResponse.getBody()))
        .isEqualTo(
            objectMapper.readTree(
                "{\"scan_id\":\"%s\",\"status\":\"identifying\"}".formatted(scanId)));
    assertThat(fakeScanQueue.getMessages()).containsExactly(new ScanMessage("jordan", scanId));
    var scanItem =
        tcgInventoryTable.getItem(
            Key.builder()
                .partitionValue(TcgInventoryItem.formatUserPk("jordan"))
                .sortValue(TcgInventoryItem.formatScanSk(scanId))
                .build());
    assertThat(scanItem.getStatus()).isEqualTo("identifying");
    assertThat(scanItem.getUpdatedAt()).isEqualTo(Instant.ofEpochSecond(1700000100));
    assertThat(rows).hasSize(2);
  }

  @Test
  void identifyScanShouldRejectIncompleteUploadsWithoutTransitioning() throws Exception {
    // arrange
    var missingScanId = createScan("jordan", "missing.jpg", 5);
    var wrongSizeScanId = createScan("jordan", "wrong-size.jpg", 5);
    s3Client.putObject(
        request ->
            request
                .bucket(ScanImages.BUCKET)
                .key("users/jordan/scans/%s/000001.jpg".formatted(wrongSizeScanId))
                .contentType(ScanImages.CONTENT_TYPE),
        RequestBody.fromBytes(new byte[] {1, 2, 3, 4}));
    var wrongTypeScanId = createScan("jordan", "wrong-type.jpg", 5);
    s3Client.putObject(
        request ->
            request
                .bucket(ScanImages.BUCKET)
                .key("users/jordan/scans/%s/000001.jpg".formatted(wrongTypeScanId))
                .contentType("image/png"),
        RequestBody.fromBytes(JPEG_BYTES));

    // act
    var missingResponse = identify(missingScanId);
    var wrongSizeResponse = identify(wrongSizeScanId);
    var wrongTypeResponse = identify(wrongTypeScanId);

    // assert
    for (var response : List.of(missingResponse, wrongSizeResponse, wrongTypeResponse)) {
      assertThat(response.getStatusCode()).isEqualTo(409);
      assertThat(response.getBody())
          .contains("all scan files must be uploaded before identification");
    }
    assertThat(fakeScanQueue.getMessages()).isEmpty();
    assertThat(getScanItem("jordan", missingScanId).getStatus()).isEqualTo("uploading");
    assertThat(getScanItem("jordan", wrongSizeScanId).getStatus()).isEqualTo("uploading");
    assertThat(getScanItem("jordan", wrongTypeScanId).getStatus()).isEqualTo("uploading");
  }

  @Test
  void identifyScanShouldReturnCurrentStateForLaterStates() throws Exception {
    // arrange
    var scanId = createScan("jordan", "001.jpg", 5);
    var scanItem = getScanItem("jordan", scanId);
    scanItem.setStatus("identifying");
    tcgInventoryTable.putItem(scanItem);
    var identifyingResponse = identify(scanId);
    scanItem.setStatus("reviewing");
    tcgInventoryTable.putItem(scanItem);
    var reviewingResponse = identify(scanId);
    scanItem.setStatus("confirmed");
    tcgInventoryTable.putItem(scanItem);

    // act
    var confirmedResponse = identify(scanId);

    // assert
    assertThat(identifyingResponse.getStatusCode()).isEqualTo(202);
    assertThat(objectMapper.readTree(identifyingResponse.getBody()).get("status").asText())
        .isEqualTo("identifying");
    assertThat(reviewingResponse.getStatusCode()).isEqualTo(202);
    assertThat(objectMapper.readTree(reviewingResponse.getBody()).get("status").asText())
        .isEqualTo("reviewing");
    assertThat(confirmedResponse.getStatusCode()).isEqualTo(202);
    assertThat(objectMapper.readTree(confirmedResponse.getBody()).get("status").asText())
        .isEqualTo("confirmed");
    assertThat(fakeScanQueue.getMessages()).isEmpty();
  }

  @Test
  void identifyScanShouldRejectMissingAndForeignScans() throws Exception {
    // arrange
    var scanId = createScan("alice", "001.jpg", 5);
    tcgInventoryTable.deleteItem(
        Key.builder()
            .partitionValue(TcgInventoryItem.formatUserPk("alice"))
            .sortValue(TcgInventoryItem.formatScanSk(scanId))
            .build());

    // act
    var deletedResponse =
        identifyScanHandler.handleRequest(
            buildEventWithPath("alice", Map.of("scan_id", scanId)), null);
    var foreignScanId = createScan("alice", "002.jpg", 5);
    var foreignResponse =
        identifyScanHandler.handleRequest(
            buildEventWithPath("bob", Map.of("scan_id", foreignScanId)), null);

    // assert
    assertThat(deletedResponse.getStatusCode()).isEqualTo(404);
    assertThat(foreignResponse.getStatusCode()).isEqualTo(404);
    assertThat(fakeScanQueue.getMessages()).isEmpty();
  }

  @Test
  void scanDetailShouldExposePersistedRecognitionResults() throws Exception {
    // arrange
    var createResponse =
        createScanHandler.handleRequest(
            buildEventWithBody(
                "jordan",
                "{\"condition\":\"NM\",\"finish\":\"normal\",\"files\":["
                    + "{\"filename\":\"001.jpg\",\"size_bytes\":5},"
                    + "{\"filename\":\"002.jpg\",\"size_bytes\":5}]}"),
            null);
    var scanId = objectMapper.readTree(createResponse.getBody()).get("scan_id").asText();
    var scanItem = getScanItem("jordan", scanId);
    scanItem.setStatus("identifying");
    scanItem.setError("one row needs manual review");
    tcgInventoryTable.putItem(scanItem);
    var row =
        tcgInventoryTable.getItem(
            Key.builder()
                .partitionValue(TcgInventoryItem.formatScanRowPk("jordan", scanId))
                .sortValue(TcgInventoryItem.formatScanRowSk(1))
                .build());
    row.setStatus("suggested");
    row.setSuggestions(
        List.of(
            TcgInventoryItem.ScanSuggestion.create(
                "a9738cda-adb1-47fb-9f4c-ecd930228c4d", "Ragavan, Nimble Pilferer", 0.8300001)));
    row.setNeedsReview(false);
    tcgInventoryTable.putItem(row);
    var reviewRow =
        tcgInventoryTable.getItem(
            Key.builder()
                .partitionValue(TcgInventoryItem.formatScanRowPk("jordan", scanId))
                .sortValue(TcgInventoryItem.formatScanRowSk(2))
                .build());
    reviewRow.setStatus("needs_review");
    reviewRow.setNeedsReview(true);
    reviewRow.setError("corrupt JPEG");
    tcgInventoryTable.putItem(reviewRow);

    // act
    var response =
        getScanHandler.handleRequest(buildEventWithPath("jordan", Map.of("scan_id", scanId)), null);

    // assert
    var body = objectMapper.readTree(response.getBody());
    assertThat(response.getStatusCode()).isEqualTo(200);
    assertThat(body.has("processed_count")).isFalse();
    assertThat(body.get("error").asText()).isEqualTo("one row needs manual review");
    assertThat(body.get("rows").get(0).get("status").asText()).isEqualTo("suggested");
    assertThat(body.get("rows").get(0).get("needs_review").asBoolean()).isFalse();
    assertThat(body.get("rows").get(0).get("suggestions").get(0).get("score").asDouble())
        .isEqualTo(0.8300001);
    assertThat(body.get("rows").get(1).get("status").asText()).isEqualTo("needs_review");
    assertThat(body.get("rows").get(1).get("needs_review").asBoolean()).isTrue();
    assertThat(body.get("rows").get(1).get("error").asText()).isEqualTo("corrupt JPEG");
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

  @Test
  void deleteScanRowShouldDeleteObjectPreserveCountsAndPositions() throws Exception {
    // arrange
    var scanId = createScanWithFiles("jordan", 3);
    var scanItem = getScanItem("jordan", scanId);
    scanItem.setStatus("reviewing");
    tcgInventoryTable.putItem(scanItem);
    var rows = factory.tcgInventoryItemRepository().findScanRows("jordan", scanId);
    for (var row : rows) {
      row.setStatus("suggested");
      tcgInventoryTable.putItem(row);
      s3Client.putObject(
          request -> request.bucket(ScanImages.BUCKET).key(row.getS3Key()),
          RequestBody.fromBytes(JPEG_BYTES));
    }
    var middleRow = rows.get(1);

    // act
    var response =
        deleteScanRowHandler.handleRequest(
            buildEventWithPath("jordan", Map.of("scan_id", scanId, "scan_position", "2")), null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(204);
    var detail =
        objectMapper.readTree(
            getScanHandler
                .handleRequest(buildEventWithPath("jordan", Map.of("scan_id", scanId)), null)
                .getBody());
    assertThat(detail.get("row_count").asInt()).isEqualTo(3);
    assertThat(detail.has("processed_count")).isFalse();
    assertThat(detail.get("rows").findValuesAsText("scan_position")).containsExactly("1", "3");
    assertThat(
            tcgInventoryTable.getItem(
                Key.builder()
                    .partitionValue(TcgInventoryItem.formatScanRowPk("jordan", scanId))
                    .sortValue(TcgInventoryItem.formatScanRowSk(2))
                    .build()))
        .isNull();
    assertThatThrownBy(
            () ->
                s3Client.headObject(
                    request -> request.bucket(ScanImages.BUCKET).key(middleRow.getS3Key())))
        .isInstanceOfSatisfying(
            S3Exception.class, exception -> assertThat(exception.statusCode()).isEqualTo(404));
  }

  @Test
  void deleteScanRowShouldRejectMissingForeignAndImmutableRows() throws Exception {
    // arrange
    var scanId = createScan("alice", "001.jpg", 5);
    var scan = getScanItem("alice", scanId);
    scan.setStatus("reviewing");
    tcgInventoryTable.putItem(scan);

    // act
    var foreignResponse =
        deleteScanRowHandler.handleRequest(
            buildEventWithPath("bob", Map.of("scan_id", scanId, "scan_position", "1")), null);
    var missingResponse =
        deleteScanRowHandler.handleRequest(
            buildEventWithPath("alice", Map.of("scan_id", "missing", "scan_position", "1")), null);
    var firstResponse =
        deleteScanRowHandler.handleRequest(
            buildEventWithPath("alice", Map.of("scan_id", scanId, "scan_position", "1")), null);
    var repeatedResponse =
        deleteScanRowHandler.handleRequest(
            buildEventWithPath("alice", Map.of("scan_id", scanId, "scan_position", "1")), null);

    // assert
    assertThat(foreignResponse.getStatusCode()).isEqualTo(404);
    assertThat(missingResponse.getStatusCode()).isEqualTo(404);
    assertThat(firstResponse.getStatusCode()).isEqualTo(204);
    assertThat(repeatedResponse.getStatusCode()).isEqualTo(404);

    var identifyingScanId = createScan("alice", "002.jpg", 5);
    var identifyingScan = getScanItem("alice", identifyingScanId);
    identifyingScan.setStatus("identifying");
    tcgInventoryTable.putItem(identifyingScan);
    var conflictResponse =
        deleteScanRowHandler.handleRequest(
            buildEventWithPath("alice", Map.of("scan_id", identifyingScanId, "scan_position", "1")),
            null);
    assertThat(conflictResponse.getStatusCode()).isEqualTo(409);
  }

  @Test
  void deleteScanRowShouldRejectConfirmedScans() throws Exception {
    // arrange
    var confirmedScanId = createScan("jordan", "confirmed.jpg", 5);
    var confirmedScan = getScanItem("jordan", confirmedScanId);
    confirmedScan.setStatus("confirmed");
    tcgInventoryTable.putItem(confirmedScan);

    // act
    var confirmedResponse =
        deleteScanRowHandler.handleRequest(
            buildEventWithPath("jordan", Map.of("scan_id", confirmedScanId, "scan_position", "1")),
            null);

    // assert
    assertThat(confirmedResponse.getStatusCode()).isEqualTo(409);
  }

  @Test
  void deleteScanShouldRemoveParentRowsAndObjectsForEveryUnfinishedState() throws Exception {
    // arrange
    var scanIds =
        List.of(
            createScan("jordan", "uploading.jpg", 5),
            createScan("jordan", "identifying.jpg", 5),
            createScan("jordan", "reviewing.jpg", 5));
    var statuses = List.of("uploading", "identifying", "reviewing");
    var keys = new ArrayList<String>();
    for (var index = 0; index < scanIds.size(); index++) {
      var scanId = scanIds.get(index);
      var scan = getScanItem("jordan", scanId);
      scan.setStatus(statuses.get(index));
      tcgInventoryTable.putItem(scan);
      var row = factory.tcgInventoryItemRepository().findScanRows("jordan", scanId).get(0);
      keys.add(row.getS3Key());
      s3Client.putObject(
          request -> request.bucket(ScanImages.BUCKET).key(row.getS3Key()),
          RequestBody.fromBytes(JPEG_BYTES));
    }

    // act
    var responses =
        scanIds.stream()
            .map(
                scanId ->
                    deleteScanHandler.handleRequest(
                        buildEventWithPath("jordan", Map.of("scan_id", scanId)), null))
            .toList();

    // assert
    assertThat(responses).allMatch(response -> response.getStatusCode() == 204);
    for (var index = 0; index < scanIds.size(); index++) {
      var scanId = scanIds.get(index);
      assertThat(getScanItem("jordan", scanId)).isNull();
      assertThat(factory.tcgInventoryItemRepository().findScanRows("jordan", scanId)).isEmpty();
      var key = keys.get(index);
      assertThatThrownBy(
              () -> s3Client.headObject(request -> request.bucket(ScanImages.BUCKET).key(key)))
          .isInstanceOfSatisfying(
              S3Exception.class, exception -> assertThat(exception.statusCode()).isEqualTo(404));
    }
  }

  @Test
  void deleteScanShouldFenceConfirmedAndStaleWorkerWrites() throws Exception {
    // arrange
    var confirmedScanId = createScan("jordan", "confirmed.jpg", 5);
    var confirmedScan = getScanItem("jordan", confirmedScanId);
    confirmedScan.setStatus("confirmed");
    tcgInventoryTable.putItem(confirmedScan);
    var scanId = createScan("jordan", "001.jpg", 5);
    var row = factory.tcgInventoryItemRepository().findScanRows("jordan", scanId).get(0);

    // act
    var confirmedResponse =
        deleteScanHandler.handleRequest(
            buildEventWithPath("jordan", Map.of("scan_id", confirmedScanId)), null);
    var deleteResponse =
        deleteScanHandler.handleRequest(
            buildEventWithPath("jordan", Map.of("scan_id", scanId)), null);
    var identifyResponse =
        identifyScanHandler.handleRequest(
            buildEventWithPath("jordan", Map.of("scan_id", scanId)), null);

    // assert
    assertThat(confirmedResponse.getStatusCode()).isEqualTo(409);
    assertThat(deleteResponse.getStatusCode()).isEqualTo(204);
    assertThat(identifyResponse.getStatusCode()).isEqualTo(404);
    assertThat(fakeScanQueue.getMessages()).isEmpty();
    assertThatThrownBy(
            () ->
                factory
                    .dynamoDbClient()
                    .updateItem(
                        UpdateItemRequest.builder()
                            .tableName(TcgInventoryItem.TABLE_NAME)
                            .key(
                                Map.of(
                                    TcgInventoryItem.PK,
                                    AttributeValue.builder().s(row.getPk()).build(),
                                    TcgInventoryItem.SK,
                                    AttributeValue.builder().s(row.getSk()).build()))
                            .updateExpression("SET #status = :suggested")
                            .conditionExpression("attribute_exists(pk)")
                            .expressionAttributeNames(Map.of("#status", TcgInventoryItem.STATUS))
                            .expressionAttributeValues(
                                Map.of(
                                    ":suggested", AttributeValue.builder().s("suggested").build()))
                            .build()))
        .isInstanceOf(ConditionalCheckFailedException.class);
  }

  @Test
  void confirmScanShouldCreateOrdinaryImportAndQueueAppraisal() throws Exception {
    // arrange
    var scanId = createScanWithFiles("jordan", 2);
    var scan = getScanItem("jordan", scanId);
    scan.setStatus("reviewing");
    tcgInventoryTable.putItem(scan);
    var request =
        "{\"rows\":["
            + "{\"scan_position\":1,\"scryfall_id\":\"opaque-card-id\","
            + "\"name\":\"Ragavan, Nimble Pilferer\",\"set_code\":\"mh2\","
            + "\"set_name\":\"Modern Horizons 2\",\"collector_number\":\"138\"},"
            + "{\"scan_position\":2,\"scryfall_id\":\"4ced112a-e775-4f97-97b3-74877e9dce12\","
            + "\"name\":\"Dragon's Rage Channeler\",\"set_code\":\"mh2\","
            + "\"set_name\":\"Modern Horizons 2\",\"collector_number\":\"121\"}]}";

    // act
    var response =
        confirmScanHandler.handleRequest(
            buildEventWithPathAndBody("jordan", Map.of("scan_id", scanId), request), null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(200);
    var body = objectMapper.readTree(response.getBody());
    assertThat(body.get("scan_id").asText()).isEqualTo(scanId);
    assertThat(body.get("status").asText()).isEqualTo("confirmed");
    assertThat(body.has("confirmed")).isFalse();
    var importId = body.get("import_id").asText();
    var importItem =
        tcgInventoryTable.getItem(
            Key.builder()
                .partitionValue(TcgInventoryItem.formatUserPk("jordan"))
                .sortValue(TcgInventoryItem.formatImportSk(importId))
                .build());
    assertThat(importItem).isNotNull();
    assertThat(importItem.getStatus()).isEqualTo("appraising");
    assertThat(importItem.getRowCount()).isEqualTo(2);
    assertThat(importItem.getJobId()).isNotBlank();

    var importRows =
        tcgInventoryTable
            .query(
                QueryEnhancedRequest.builder()
                    .queryConditional(
                        QueryConditional.sortBeginsWith(
                            Key.builder()
                                .partitionValue(
                                    TcgInventoryItem.formatImportRowPk("jordan", importId))
                                .sortValue(TcgInventoryItem.ROW_PREFIX)
                                .build()))
                    .scanIndexForward(true)
                    .build())
            .stream()
            .flatMap(page -> page.items().stream())
            .toList();
    assertThat(importRows).extracting(TcgInventoryItem::getPosition).containsExactly(1, 2);
    assertThat(importRows)
        .extracting(TcgInventoryItem::getName)
        .containsExactly("Ragavan, Nimble Pilferer", "Dragon's Rage Channeler");
    assertThat(importRows)
        .extracting(TcgInventoryItem::getScryfallId)
        .containsExactly("opaque-card-id", "4ced112a-e775-4f97-97b3-74877e9dce12");
    assertThat(importRows).allMatch(row -> "en".equals(row.getLanguage()));

    var jobItem =
        tcgInventoryTable.getItem(
            Key.builder()
                .partitionValue(TcgInventoryItem.formatUserPk("jordan"))
                .sortValue(TcgInventoryItem.formatJobSk(importItem.getJobId()))
                .build());
    assertThat(jobItem).isNotNull();
    assertThat(jobItem.getStatus()).isEqualTo("queued");
    assertThat(fakeJobsQueue.getSends()).hasSize(1);
    assertThat(fakeJobsQueue.getSends().get(0).message().jobId()).isEqualTo(importItem.getJobId());
    assertThat(getScanItem("jordan", scanId).getStatus()).isEqualTo("confirmed");
    var retryResponse =
        confirmScanHandler.handleRequest(
            buildEventWithPathAndBody("jordan", Map.of("scan_id", scanId), request), null);
    assertThat(retryResponse.getStatusCode()).isEqualTo(200);
    assertThat(objectMapper.readTree(retryResponse.getBody()).get("import_id").asText())
        .isEqualTo(importId);
    assertThat(fakeJobsQueue.getSends()).hasSize(1);
    assertThat(
            objectMapper
                .readTree(
                    getScanHandler
                        .handleRequest(
                            buildEventWithPath("jordan", Map.of("scan_id", scanId)), null)
                        .getBody())
                .has("confirmed_rows"))
        .isFalse();
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

  private String createScanWithFiles(String user, int fileCount) throws IOException {
    var files =
        IntStream.rangeClosed(1, fileCount)
            .mapToObj(index -> "{\"filename\":\"%03d.jpg\",\"size_bytes\":5}".formatted(index))
            .collect(Collectors.joining(","));
    var response =
        createScanHandler.handleRequest(
            buildEventWithBody(
                user, "{\"condition\":\"NM\",\"finish\":\"normal\",\"files\":[" + files + "]}"),
            null);
    assertThat(response.getStatusCode()).isEqualTo(201);
    return objectMapper.readTree(response.getBody()).get("scan_id").asText();
  }

  private TcgInventoryItem getScanItem(String user, String scanId) {
    return tcgInventoryTable.getItem(
        Key.builder()
            .partitionValue(TcgInventoryItem.formatUserPk(user))
            .sortValue(TcgInventoryItem.formatScanSk(scanId))
            .build());
  }

  private APIGatewayV2HTTPResponse identify(String scanId) {
    return identifyScanHandler.handleRequest(
        buildEventWithPath("jordan", Map.of("scan_id", scanId)), null);
  }

  private APIGatewayV2HTTPEvent buildEventWithPathAndBody(
      String user, Map<String, String> pathParams, String body) {
    var event = buildEventWithPath(user, pathParams);
    event.setBody(body);
    return event;
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
