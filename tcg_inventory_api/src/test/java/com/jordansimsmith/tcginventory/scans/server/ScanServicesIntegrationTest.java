package com.jordansimsmith.tcginventory.scans.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jordansimsmith.dynamodb.DynamoDbContainer;
import com.jordansimsmith.dynamodb.DynamoDbUtils;
import com.jordansimsmith.s3.S3Container;
import com.jordansimsmith.service.BadRequestException;
import com.jordansimsmith.service.NotFoundException;
import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.tcginventory.scans.client.FindScansRequest;
import com.jordansimsmith.tcginventory.scans.client.GetScanRequest;
import com.jordansimsmith.tcginventory.scans.client.ScanRowStatus;
import com.jordansimsmith.tcginventory.scans.client.ScanStatus;
import com.jordansimsmith.tcginventory.scans.client.ScanSuggestion;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

@Testcontainers
class ScanServicesIntegrationTest {
  private static final String TABLE_NAME = "scan_services_test";
  private static final String BUCKET = "scan-services-test";
  private static final Instant CREATED_AT = Instant.ofEpochSecond(1_700_000_000);

  @Container private static final DynamoDbContainer dynamoDbContainer = new DynamoDbContainer();
  @Container private static final S3Container s3Container = new S3Container();

  private static DynamoDbClient dynamoDbClient;
  private static DynamoDbTable<ScanItem> scanTable;
  private static DynamoDbTable<ScanRowItem> scanRowTable;
  private static S3Client s3Client;
  private static S3Presigner s3Presigner;

  private FindScansService findScansService;
  private GetScanService getScanService;

  @BeforeAll
  static void setUpBeforeClass() {
    var credentials = StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test"));
    dynamoDbClient =
        DynamoDbClient.builder()
            .endpointOverride(dynamoDbContainer.getEndpoint())
            .region(Region.US_EAST_1)
            .credentialsProvider(credentials)
            .build();
    var enhancedClient = DynamoDbEnhancedClient.builder().dynamoDbClient(dynamoDbClient).build();
    scanTable = enhancedClient.table(TABLE_NAME, TableSchema.fromBean(ScanItem.class));
    scanRowTable = enhancedClient.table(TABLE_NAME, TableSchema.fromBean(ScanRowItem.class));
    DynamoDbUtils.createTable(dynamoDbClient, scanTable);

    s3Client =
        S3Client.builder()
            .endpointOverride(s3Container.getEndpoint())
            .region(Region.US_EAST_1)
            .credentialsProvider(credentials)
            .forcePathStyle(true)
            .build();
    s3Presigner =
        S3Presigner.builder()
            .endpointOverride(s3Container.getEndpoint())
            .region(Region.US_EAST_1)
            .credentialsProvider(credentials)
            .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
            .build();
    s3Client.createBucket(request -> request.bucket(BUCKET));
  }

  @AfterAll
  static void tearDownAfterClass() {
    s3Presigner.close();
    s3Client.close();
    dynamoDbClient.close();
  }

  @BeforeEach
  void setUp() {
    DynamoDbUtils.reset(dynamoDbClient);
    for (var object : s3Client.listObjectsV2(request -> request.bucket(BUCKET)).contents()) {
      s3Client.deleteObject(request -> request.bucket(BUCKET).key(object.key()));
    }
    var repository = new ScanReadRepository(scanTable, scanRowTable);
    findScansService = new FindScansService(repository, new ObjectMapper());
    getScanService = new GetScanService(repository, s3Client, s3Presigner, BUCKET);
  }

  @Test
  void findScansShouldReturnNewestFirstPagesAndRoundTripContinuation() throws Exception {
    // arrange
    putScan("alice", "scan-001", "uploading", 1);
    putScan("alice", "scan-002", "reviewing", 2);
    putScan("alice", "scan-003", "confirmed", 3);

    // act
    var firstPage =
        findScansService.findScans(new RequestContext("alice"), new FindScansRequest(2, null));
    var secondPage =
        findScansService.findScans(
            new RequestContext("alice"), new FindScansRequest(2, firstPage.nextContinuation()));

    // assert
    assertThat(firstPage.scans()).extracting("scanId").containsExactly("scan-003", "scan-002");
    assertThat(firstPage.nextContinuation()).isNotBlank();
    assertThat(secondPage.scans()).extracting("scanId").containsExactly("scan-001");
    assertThat(secondPage.nextContinuation()).isNull();
  }

  @Test
  void findScansShouldUseDefaultLimitAndAcceptMaximumLimit() throws Exception {
    // arrange
    for (int index = 1; index <= 21; index++) {
      putScan("alice", "scan-%03d".formatted(index), "reviewing", index);
    }

    // act
    var defaultPage =
        findScansService.findScans(new RequestContext("alice"), new FindScansRequest(null, null));
    var maximumPage =
        findScansService.findScans(new RequestContext("alice"), new FindScansRequest(100, null));

    // assert
    assertThat(defaultPage.scans()).hasSize(20);
    assertThat(defaultPage.scans().getFirst().scanId()).isEqualTo("scan-021");
    assertThat(defaultPage.nextContinuation()).isNotBlank();
    assertThat(maximumPage.scans()).hasSize(21);
    assertThat(maximumPage.nextContinuation()).isNull();
  }

  @Test
  void findScansShouldRejectInvalidLimitsAndMalformedContinuations() {
    // arrange
    var ctx = new RequestContext("alice");

    // act / assert
    for (var limit : List.of(0, -1)) {
      assertThatThrownBy(() -> findScansService.findScans(ctx, new FindScansRequest(limit, null)))
          .isInstanceOf(BadRequestException.class)
          .hasMessage("limit must be positive");
    }
    assertThatThrownBy(() -> findScansService.findScans(ctx, new FindScansRequest(101, null)))
        .isInstanceOf(BadRequestException.class)
        .hasMessage("limit must not exceed 100");
    assertThatThrownBy(
            () -> findScansService.findScans(ctx, new FindScansRequest(null, "invalid-token")))
        .isInstanceOf(BadRequestException.class)
        .hasMessage("Invalid continuation");
  }

  @Test
  void getScanShouldPreserveMetadataRetainedPositionsAndProjectPendingRows() throws Exception {
    // arrange
    var scan = scan("alice", "scan-1", "reviewing", 8);
    scan.setImportId("import-1");
    scanTable.putItem(scan);
    var suggested = row("alice", "scan-1", 1, "suggested-key", 10);
    suggested.setStatus("suggested");
    suggested.setSuggestions(
        List.of(ScanRowItem.ScanSuggestion.create("printing-1", "Card name", 0.92)));
    var pending = row("alice", "scan-1", 5, "pending-key", 10);
    pending.setNeedsReview(true);
    pending.setSuggestions(null);
    scanRowTable.putItem(suggested);
    scanRowTable.putItem(pending);

    // act
    var response =
        getScanService.getScan(new RequestContext("alice"), new GetScanRequest("scan-1"));

    // assert
    assertThat(response.scanId()).isEqualTo("scan-1");
    assertThat(response.game()).isEqualTo("mtg");
    assertThat(response.status()).isEqualTo(ScanStatus.REVIEWING);
    assertThat(response.condition()).isEqualTo("NM");
    assertThat(response.finish()).isEqualTo("normal");
    assertThat(response.rowCount()).isEqualTo(8);
    assertThat(response.importId()).isEqualTo("import-1");
    assertThat(response.createdAt()).isEqualTo(CREATED_AT.getEpochSecond());
    assertThat(response.rows()).extracting("scanPosition").containsExactly(1, 5);
    assertThat(response.rows().getFirst().status()).isEqualTo(ScanRowStatus.SUGGESTED);
    assertThat(response.rows().getFirst().suggestions())
        .containsExactly(new ScanSuggestion("printing-1", "Card name", 0.92));
    assertThat(response.rows().get(1).status()).isNull();
    assertThat(response.rows().get(1).needsReview()).isTrue();
    assertThat(response.rows().get(1).suggestions()).isEmpty();
    assertThat(response.rows()).allMatch(row -> row.sourceUrl() != null);
  }

  @Test
  void getScanShouldCheckUploadSizeAndContentTypeAndSkipHeadChecksLater() throws Exception {
    // arrange
    putScan("alice", "uploading-scan", "uploading", 4);
    putRow("alice", "uploading-scan", 1, "missing.jpg", "missing", 10);
    putRow("alice", "uploading-scan", 2, "wrong-size.jpg", "wrong-size", 10);
    putRow("alice", "uploading-scan", 3, "wrong-type.jpg", "wrong-type", 10);
    putRow("alice", "uploading-scan", 4, "valid.jpg", "valid", 10);
    putObject("wrong-size", 9, "image/jpeg");
    putObject("wrong-type", 10, "image/png");
    putObject("valid", 10, "image/jpeg");

    // act
    var uploading =
        getScanService.getScan(new RequestContext("alice"), new GetScanRequest("uploading-scan"));
    putScan("alice", "reviewing-scan", "reviewing", 1);
    putRow("alice", "reviewing-scan", 1, "absent-after-upload.jpg", "absent", 10);
    var reviewing =
        getScanService.getScan(new RequestContext("alice"), new GetScanRequest("reviewing-scan"));

    // assert
    assertThat(uploading.rows()).extracting("uploaded").containsExactly(false, false, false, true);
    assertThat(uploading.rows())
        .filteredOn(row -> row.sourceUrl() == null)
        .extracting("scanPosition")
        .containsExactly(1, 2, 3);
    assertThat(uploading.rows().get(3).sourceUrl()).isNotBlank().contains("X-Amz-Expires=900");
    assertThat(reviewing.rows().getFirst().uploaded()).isTrue();
    assertThat(reviewing.rows().getFirst().sourceUrl()).contains("X-Amz-Expires=900");
  }

  @Test
  void getScanShouldReturnNotFoundForMissingOrForeignScanAndRejectBlankId() {
    // arrange
    putScan("alice", "private-scan", "reviewing", 1);

    // act / assert
    assertThatThrownBy(
            () -> getScanService.getScan(new RequestContext("alice"), new GetScanRequest(" ")))
        .isInstanceOf(BadRequestException.class)
        .hasMessage("scan_id is required");
    assertThatThrownBy(
            () ->
                getScanService.getScan(
                    new RequestContext("bob"), new GetScanRequest("private-scan")))
        .isInstanceOf(NotFoundException.class)
        .hasMessage("Not Found");
    assertThatThrownBy(
            () ->
                getScanService.getScan(new RequestContext("alice"), new GetScanRequest("missing")))
        .isInstanceOf(NotFoundException.class)
        .hasMessage("Not Found");
  }

  private static ScanItem scan(String user, String scanId, String status, int rowCount) {
    var scan = ScanItem.create(user, "mtg", scanId, "NM", "normal", rowCount, CREATED_AT);
    scan.setStatus(status);
    return scan;
  }

  private static void putScan(String user, String scanId, String status, int rowCount) {
    scanTable.putItem(scan(user, scanId, status, rowCount));
  }

  private static ScanRowItem row(
      String user, String scanId, int position, String key, long sizeBytes) {
    return ScanRowItem.create(
        user, scanId, position, "row-%d.jpg".formatted(position), sizeBytes, key);
  }

  private static void putRow(
      String user, String scanId, int position, String filename, String key, long sizeBytes) {
    var row = ScanRowItem.create(user, scanId, position, filename, sizeBytes, key);
    scanRowTable.putItem(row);
  }

  private static void putObject(String key, int sizeBytes, String contentType) {
    s3Client.putObject(
        PutObjectRequest.builder().bucket(BUCKET).key(key).contentType(contentType).build(),
        RequestBody.fromBytes(new byte[sizeBytes]));
  }
}
