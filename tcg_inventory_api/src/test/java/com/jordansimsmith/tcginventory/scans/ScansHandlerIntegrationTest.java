package com.jordansimsmith.tcginventory.scans;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.jordansimsmith.dynamodb.DynamoDbContainer;
import com.jordansimsmith.dynamodb.DynamoDbUtils;
import com.jordansimsmith.queue.FakeQueueClient;
import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.tcginventory.JobItem;
import com.jordansimsmith.tcginventory.JobMessage;
import com.jordansimsmith.tcginventory.TcgInventoryTable;
import com.jordansimsmith.tcginventory.TcgInventoryTestFactory;
import com.jordansimsmith.tcginventory.catalog.CatalogCard;
import com.jordansimsmith.tcginventory.catalog.CatalogException;
import com.jordansimsmith.tcginventory.catalog.FakeCardCatalogs;
import com.jordansimsmith.tcginventory.imports.client.FakeImportService;
import com.jordansimsmith.tcginventory.imports.client.FindImportsRequest;
import com.jordansimsmith.tcginventory.imports.client.GetImportRequest;
import com.jordansimsmith.tcginventory.imports.client.ImportStatus;
import com.jordansimsmith.time.FakeClock;
import com.jordansimsmith.ulid.FakeUlidGenerator;
import java.nio.charset.StandardCharsets;
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
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;

@Testcontainers
public class ScansHandlerIntegrationTest {
  @Container private static final DynamoDbContainer dynamoDbContainer = new DynamoDbContainer();

  private TcgInventoryTestFactory factory;
  private FakeClock fakeClock;
  private FakeUlidGenerator fakeUlidGenerator;
  private FakeQueueClient<JobMessage> fakeJobsQueue;
  private FakeCardCatalogs fakeCardCatalogs;
  private DynamoDbTable<ScanItem> scanTable;
  private DynamoDbTable<ScanRowItem> scanRowTable;
  private DynamoDbTable<JobItem> jobTable;
  private ConfirmScanHandler confirmScanHandler;
  private FakeImportService fakeImportService;
  private ScanConfirmationJobProcessor scanConfirmationJobProcessor;

  @BeforeAll
  static void setUpBeforeClass() {
    var factory =
        TcgInventoryTestFactory.create(
            dynamoDbContainer.getEndpoint(), TcgInventoryTestFactory.UNUSED_S3_ENDPOINT);
    DynamoDbUtils.createTable(factory.dynamoDbClient(), factory.tableDefinition());
  }

  @BeforeEach
  void setUp() {
    factory =
        TcgInventoryTestFactory.create(
            dynamoDbContainer.getEndpoint(), TcgInventoryTestFactory.UNUSED_S3_ENDPOINT);
    fakeClock = factory.fakeClock();
    fakeUlidGenerator = factory.fakeUlidGenerator();
    fakeJobsQueue = factory.fakeJobsQueue();
    scanTable = factory.scanTable();
    scanRowTable = factory.scanRowTable();
    jobTable = factory.jobTable();
    DynamoDbUtils.reset(factory.dynamoDbClient());
    fakeUlidGenerator.reset();
    fakeJobsQueue.reset();
    fakeCardCatalogs = factory.fakeCardCatalogs();
    fakeImportService = new FakeImportService(factory.photosService());
    fakeCardCatalogs.addCard(
        new CatalogCard(
            "mtg",
            "a9738cda-adb1-47fb-9f4c-ecd930228c4d",
            "Ragavan, Nimble Pilferer",
            "mh2",
            "Modern Horizons 2",
            "138",
            new CatalogCard.ImageUrls(null, null),
            List.of("normal", "foil")));
    fakeCardCatalogs.addCard(
        new CatalogCard(
            "mtg",
            "4ced112a-e775-4f97-97b3-74877e9dce12",
            "Dragon's Rage Channeler",
            "mh2",
            "Modern Horizons 2",
            "121",
            new CatalogCard.ImageUrls(null, null),
            List.of("normal", "foil")));
    confirmScanHandler = new ConfirmScanHandler(factory);
    scanConfirmationJobProcessor =
        new ScanConfirmationJobProcessor(
            factory.scanRepository(),
            factory.catalogs(),
            factory.gamesService(),
            fakeImportService);
  }

  @Test
  void confirmScanShouldPersistSelectionsAndCreateImportInJob() throws Exception {
    // arrange
    var user = "confirm-success";
    var scanId = createScanWithFiles(user, 2);
    var scan = getScanItem(user, scanId);
    scan.setStatus("reviewing");
    scanTable.putItem(scan);
    var request =
        "{\"rows\":[{\"scan_position\":1,\"external_id\":\"a9738cda-adb1-47fb-9f4c-ecd930228c4d\",\"name\":\"Forged"
            + " name one\",\"set_code\":\"fake\",\"set_name\":\"Forged set"
            + " one\",\"collector_number\":\"999\"},{\"scan_position\":2,\"external_id\":\"4ced112a-e775-4f97-97b3-74877e9dce12\",\"name\":\"Forged"
            + " name two\",\"set_code\":\"fake\",\"set_name\":\"Forged set"
            + " two\",\"collector_number\":\"998\"}]}";

    // act
    var response =
        confirmScanHandler.handleRequest(
            buildEventWithPathAndBody(user, Map.of("scan_id", scanId), request), null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(202);
    assertThat(response.getBody()).isNull();
    assertThat(getScanItem(user, scanId).getStatus()).isEqualTo("confirming");
    assertThat(fakeJobsQueue.getSends()).hasSize(1);
    var confirmationMessage = fakeJobsQueue.getSends().getFirst().message();
    assertThat(confirmationMessage.jobType()).isEqualTo("scan_confirmation");
    var confirmationJob = getJob(user, confirmationMessage.jobId());
    assertThat(confirmationJob.getScanId()).isEqualTo(scanId);
    assertThat(getScanRow(user, scanId, 1).getSelectedExternalId())
        .isEqualTo("a9738cda-adb1-47fb-9f4c-ecd930228c4d");
    assertThat(getScanRow(user, scanId, 2).getSelectedExternalId())
        .isEqualTo("4ced112a-e775-4f97-97b3-74877e9dce12");
    assertNoCreatedImports(user);
    var repeatedResponse =
        confirmScanHandler.handleRequest(
            buildEventWithPathAndBody(user, Map.of("scan_id", scanId), request), null);
    assertThat(repeatedResponse.getStatusCode()).isEqualTo(202);
    assertThat(repeatedResponse.getBody()).isNull();
    assertThat(fakeJobsQueue.getSends()).hasSize(1);

    // act
    processScanConfirmation(user);

    // assert
    var importId = getScanItem(user, scanId).getImportId();
    var importDetail =
        fakeImportService.getImport(new RequestContext(user), new GetImportRequest(importId));
    assertThat(importDetail.status()).isEqualTo(ImportStatus.APPRAISING);
    assertThat(importDetail.game()).isEqualTo("mtg");
    assertThat(importDetail.filename()).isEqualTo(scanId + ".scan");
    assertThat(importDetail.rowCount()).isEqualTo(2);
    assertThat(importDetail.rows())
        .extracting(row -> row.name())
        .containsExactly("Ragavan, Nimble Pilferer", "Dragon's Rage Channeler");
    assertThat(importDetail.rows())
        .extracting(row -> row.externalId())
        .containsExactly(
            "a9738cda-adb1-47fb-9f4c-ecd930228c4d", "4ced112a-e775-4f97-97b3-74877e9dce12");
    assertThat(importDetail.rows())
        .allMatch(row -> row.finish().equals("normal") && row.condition().name().equals("NM"));
    assertThat(fakeJobsQueue.getSends()).hasSize(1);
    assertThat(getScanItem(user, scanId).getStatus()).isEqualTo("confirmed");
    var retryResponse =
        confirmScanHandler.handleRequest(
            buildEventWithPathAndBody(user, Map.of("scan_id", scanId), request), null);
    assertThat(retryResponse.getStatusCode()).isEqualTo(204);
    assertThat(retryResponse.getBody()).isNull();
    assertThat(fakeJobsQueue.getSends()).hasSize(1);
    assertThat(fakeCardCatalogs.lookupRequests()).hasSize(1);
    assertThat(fakeCardCatalogs.lookupRequests().getFirst())
        .containsExactly(
            "a9738cda-adb1-47fb-9f4c-ecd930228c4d", "4ced112a-e775-4f97-97b3-74877e9dce12");
    processScanConfirmation(user);
    assertThat(fakeCardCatalogs.lookupRequests()).hasSize(1);
  }

  @Test
  void confirmScanShouldResolveDuplicateIdsOnlyOnce() throws Exception {
    // arrange
    var user = "confirm-duplicate";
    var scanId = createScanWithFiles(user, 2);
    var scan = getScanItem(user, scanId);
    scan.setStatus("reviewing");
    scanTable.putItem(scan);
    var request =
        confirmationRequest(
            "a9738cda-adb1-47fb-9f4c-ecd930228c4d",
            "forged",
            "fake",
            "fake",
            "1",
            "a9738cda-adb1-47fb-9f4c-ecd930228c4d",
            "forged",
            "fake",
            "fake",
            "1");

    // act
    var response =
        confirmScanHandler.handleRequest(
            buildEventWithPathAndBody(user, Map.of("scan_id", scanId), request), null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(202);
    assertThat(fakeCardCatalogs.lookupRequests()).isEmpty();
    processScanConfirmation(user);
    assertThat(fakeCardCatalogs.lookupRequests()).hasSize(1);
    assertThat(fakeCardCatalogs.lookupRequests().getFirst())
        .containsExactly("a9738cda-adb1-47fb-9f4c-ecd930228c4d");
  }

  @Test
  void confirmationJobShouldUseGameIdentityWithoutSubmittedSource() throws Exception {
    // arrange
    var user = "confirm-game-identity";
    var scanId = createScanWithFiles(user, 1);
    var scan = getScanItem(user, scanId);
    scan.setStatus("reviewing");
    scanTable.putItem(scan);
    var request =
        confirmationRequest("a9738cda-adb1-47fb-9f4c-ecd930228c4d", "Ragavan", "mh2", "Set", "138");
    // act
    var response =
        confirmScanHandler.handleRequest(
            buildEventWithPathAndBody(user, Map.of("scan_id", scanId), request), null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(202);
    assertThat(getScanItem(user, scanId).getStatus()).isEqualTo("confirming");
    processScanConfirmation(user);
    assertThat(getScanItem(user, scanId).getStatus()).isEqualTo("confirmed");
    assertThat(getScanError(user, scanId)).isNull();
    assertThat(fakeCardCatalogs.lookupRequests())
        .containsExactly(List.of("a9738cda-adb1-47fb-9f4c-ecd930228c4d"));
  }

  @Test
  void confirmationJobShouldRejectMissingCardBeforeCreatingImport() throws Exception {
    // arrange
    var user = "confirm-missing";
    var scanId = createScanWithFiles(user, 1);
    var scan = getScanItem(user, scanId);
    scan.setStatus("reviewing");
    scanTable.putItem(scan);
    var missingId = "11111111-1111-4111-8111-111111111111";
    var request = confirmationRequest(missingId, "Missing", "set", "Set", "1");
    // act
    var response =
        confirmScanHandler.handleRequest(
            buildEventWithPathAndBody(user, Map.of("scan_id", scanId), request), null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(202);
    processScanConfirmation(user);
    assertThat(getScanItem(user, scanId).getStatus()).isEqualTo("reviewing");
    assertThat(getScanError(user, scanId)).contains("scan position 1");
    assertNoCreatedImports(user);
    assertThat(fakeCardCatalogs.lookupRequests()).containsExactly(List.of(missingId));
  }

  @Test
  void confirmationJobShouldRejectCardWithoutScanFinishBeforeCreatingImport() throws Exception {
    // arrange
    var user = "confirm-finish";
    var scanId = createScanWithFiles(user, 1);
    var scan = getScanItem(user, scanId);
    scan.setStatus("reviewing");
    scanTable.putItem(scan);
    var foilOnlyId = "11111111-1111-4111-8111-111111111111";
    fakeCardCatalogs.addCard(
        new CatalogCard(
            "mtg",
            foilOnlyId,
            "Foil only",
            "test",
            "Test set",
            "1",
            new CatalogCard.ImageUrls(null, null),
            List.of("foil")));
    var request = confirmationRequest(foilOnlyId, "Foil only", "test", "Test set", "1");
    // act
    var response =
        confirmScanHandler.handleRequest(
            buildEventWithPathAndBody(user, Map.of("scan_id", scanId), request), null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(202);
    processScanConfirmation(user);
    assertThat(getScanItem(user, scanId).getStatus()).isEqualTo("reviewing");
    assertThat(getScanError(user, scanId)).contains("scan position 1");
    assertNoCreatedImports(user);
  }

  @Test
  void confirmationJobShouldLeaveScanConfirmingWhenCatalogFailsTransiently() throws Exception {
    // arrange
    var user = "confirm-unavailable";
    var scanId = createScanWithFiles(user, 1);
    var scan = getScanItem(user, scanId);
    scan.setStatus("reviewing");
    scanTable.putItem(scan);
    fakeCardCatalogs.setFailure(new CatalogException.Unavailable("stub unavailable"));
    var request =
        confirmationRequest("a9738cda-adb1-47fb-9f4c-ecd930228c4d", "Ragavan", "mh2", "Set", "138");
    // act
    var response =
        confirmScanHandler.handleRequest(
            buildEventWithPathAndBody(user, Map.of("scan_id", scanId), request), null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(202);
    var message = fakeJobsQueue.getSends().getFirst().message();
    assertThatThrownBy(
            () -> scanConfirmationJobProcessor.processBatch(user, getJob(user, message.jobId())))
        .isInstanceOf(CatalogException.Unavailable.class)
        .hasMessageContaining("stub unavailable");
    assertThat(getScanItem(user, scanId).getStatus()).isEqualTo("confirming");
    assertThat(getScanError(user, scanId)).isNull();
    assertNoCreatedImports(user);

    // arrange: the catalog has recovered before SQS redelivery
    fakeCardCatalogs.setFailure(null);

    // act
    scanConfirmationJobProcessor.processBatch(user, getJob(user, message.jobId()));

    // assert
    assertThat(getScanItem(user, scanId).getStatus()).isEqualTo("confirmed");
    assertThat(getScanItem(user, scanId).getImportId()).isNotBlank();
    assertThat(fakeCardCatalogs.lookupRequests()).hasSize(2);
  }

  @Test
  void confirmScanShouldRejectBlankExternalIdentityBeforeCreatingImport() throws Exception {
    // arrange
    var scanId = createScanWithFiles("jordan", 1);
    var scan = getScanItem("jordan", scanId);
    scan.setStatus("reviewing");
    scanTable.putItem(scan);
    var request =
        "{\"rows\":[{\"scan_position\":1,\"external_id\":\"\",\"name\":\"Forest\","
            + "\"set_code\":\"lea\",\"set_name\":\"Limited Edition Alpha\","
            + "\"collector_number\":\"1\"}]}";

    // act
    var response =
        confirmScanHandler.handleRequest(
            buildEventWithPathAndBody("jordan", Map.of("scan_id", scanId), request), null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(400);
    assertThat(getScanItem("jordan", scanId).getStatus()).isEqualTo("reviewing");
    assertThat(fakeJobsQueue.getSends()).isEmpty();
    assertNoCreatedImports("jordan");
  }

  @Test
  void confirmScanShouldRejectStaleVersionAfterReviewRowChanges() {
    // arrange
    var user = "stale-confirmation";
    var scanId = createScanWithFiles(user, 1);
    var scan = getScanItem(user, scanId);
    scan.setStatus("reviewing");
    scanTable.putItem(scan);
    factory
        .dynamoDbClient()
        .updateItem(
            request ->
                request
                    .tableName(TcgInventoryTable.TABLE_NAME)
                    .key(scanKey(user, scanId))
                    .updateExpression("SET #version = :version")
                    .expressionAttributeNames(Map.of("#version", ScanItem.VERSION))
                    .expressionAttributeValues(Map.of(":version", AttributeValue.fromN("1"))));
    var job =
        JobItem.create(user, fakeUlidGenerator.generate(), "scan_confirmation", fakeClock.now());
    job.setScanId(scanId);

    // act
    assertThatThrownBy(() -> factory.scanRepository().startScanConfirmation(user, scanId, 0, job))
        .isInstanceOf(TransactionCanceledException.class);

    // assert
    assertThat(getScanItem(user, scanId).getStatus()).isEqualTo("reviewing");
    assertThat(getJob(user, job.getJobId())).isNull();
    assertThat(fakeJobsQueue.getSends()).isEmpty();
  }

  private void processScanConfirmation(String user) throws Exception {
    var message = fakeJobsQueue.getSends().getFirst().message();
    assertThat(message.jobType()).isEqualTo("scan_confirmation");
    scanConfirmationJobProcessor.processBatch(user, getJob(user, message.jobId()));
  }

  private void assertNoCreatedImports(String user) throws Exception {
    var imports =
        fakeImportService.findImports(new RequestContext(user), new FindImportsRequest(100, null));
    assertThat(imports.imports()).isEmpty();
  }

  private String confirmationRequest(String... rowValues) {
    var rows =
        IntStream.range(0, rowValues.length / 5)
            .mapToObj(
                index -> {
                  var offset = index * 5;
                  return "{\"scan_position\":%d,\"external_id\":\"%s\",\"name\":\"%s\",\"set_code\":\"%s\",\"set_name\":\"%s\",\"collector_number\":\"%s\"}"
                      .formatted(
                          index + 1,
                          rowValues[offset],
                          rowValues[offset + 1],
                          rowValues[offset + 2],
                          rowValues[offset + 3],
                          rowValues[offset + 4]);
                })
            .collect(Collectors.joining(","));
    return "{\"rows\":[" + rows + "]}";
  }

  private String createScanWithFiles(String user, int fileCount) {
    var files =
        IntStream.rangeClosed(1, fileCount)
            .mapToObj(index -> new ScanFileForTest("%03d.jpg".formatted(index), 5))
            .toList();
    return createScanWithFiles(user, files);
  }

  private String createScanWithFiles(String user, List<ScanFileForTest> files) {
    var scanId = fakeUlidGenerator.generate();
    var now = fakeClock.now();
    var scan = ScanItem.create(user, "mtg", scanId, "NM", "normal", files.size(), now);
    var rows =
        IntStream.range(0, files.size())
            .mapToObj(
                index -> {
                  var file = files.get(index);
                  var position = index + 1;
                  var key = "users/%s/scans/%s/%06d.jpg".formatted(user, scanId, position);
                  return ScanRowItem.create(
                      user, scanId, position, file.filename(), file.sizeBytes(), key);
                })
            .toList();
    factory.scanRepository().createScan(scan, rows);
    return scanId;
  }

  private record ScanFileForTest(String filename, int sizeBytes) {}

  private ScanItem getScanItem(String user, String scanId) {
    return scanTable.getItem(
        Key.builder()
            .partitionValue(ScanItem.formatPk(user))
            .sortValue(ScanItem.formatSk(scanId))
            .build());
  }

  @Nullable
  private String getScanError(String user, String scanId) {
    var response =
        factory
            .dynamoDbClient()
            .getItem(
                request ->
                    request
                        .tableName(TcgInventoryTable.TABLE_NAME)
                        .key(scanKey(user, scanId))
                        .consistentRead(true));
    var value = response.item().get("error");
    return value == null ? null : value.s();
  }

  private Map<String, AttributeValue> scanKey(String user, String scanId) {
    return Map.of(
        ScanItem.PK, AttributeValue.fromS(ScanItem.formatPk(user)),
        ScanItem.SK, AttributeValue.fromS(ScanItem.formatSk(scanId)));
  }

  private ScanRowItem getScanRow(String user, String scanId, int scanPosition) {
    return scanRowTable.getItem(
        Key.builder()
            .partitionValue(ScanRowItem.formatPk(user, scanId))
            .sortValue(ScanRowItem.formatSk(scanPosition))
            .build());
  }

  private JobItem getJob(String user, String jobId) {
    return jobTable.getItem(
        Key.builder()
            .partitionValue(JobItem.formatPk(user))
            .sortValue(JobItem.formatSk(jobId))
            .build());
  }

  private APIGatewayV2HTTPEvent buildEventWithPathAndBody(
      String user, Map<String, String> pathParams, String body) {
    var event = buildEventWithPath(user, pathParams);
    event.setBody(body);
    return event;
  }

  private APIGatewayV2HTTPEvent buildEventWithPath(String user, Map<String, String> pathParams) {
    return buildEvent(user, pathParams, Map.of());
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
