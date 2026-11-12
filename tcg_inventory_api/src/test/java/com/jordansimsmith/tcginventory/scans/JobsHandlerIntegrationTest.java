package com.jordansimsmith.tcginventory.scans;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.jordansimsmith.dynamodb.DynamoDbContainer;
import com.jordansimsmith.dynamodb.DynamoDbUtils;
import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.tcginventory.JobItem;
import com.jordansimsmith.tcginventory.JobMessage;
import com.jordansimsmith.tcginventory.JobsHandler;
import com.jordansimsmith.tcginventory.TcgInventoryTable;
import com.jordansimsmith.tcginventory.TcgInventoryTestFactory;
import com.jordansimsmith.tcginventory.catalog.CatalogCard;
import com.jordansimsmith.tcginventory.imports.client.GetImportRequest;
import com.jordansimsmith.tcginventory.imports.client.ImportStatus;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

@Testcontainers
class JobsHandlerIntegrationTest {
  private static final String USER = "scan-user";
  private static final String JOB_ID = "scan-job";
  private static final String SCAN_ID = "opaque-scan-id";
  private static final String CARD_ID = "opaque/card-id";
  private static final Instant NOW = Instant.ofEpochSecond(1_700_000_000);

  @Container private static final DynamoDbContainer dynamoDbContainer = new DynamoDbContainer();

  private TcgInventoryTestFactory factory;
  private JobsHandler jobsHandler;

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
    DynamoDbUtils.reset(factory.dynamoDbClient());
    factory.fakeImportsQueue().reset();
    factory.fakeUlidGenerator().reset();
    jobsHandler = new JobsHandler(factory);
  }

  @Test
  void handleRequestShouldConfirmThroughImportsServiceWhenScanJobIsQueued() throws Exception {
    // arrange
    factory.fakeCardCatalogs().addCard(card());
    saveConfirmingScan();
    var job = JobItem.create(USER, JOB_ID, "scan_confirmation", NOW);
    job.setScanId(SCAN_ID);
    factory.jobTable().putItem(job);
    factory
        .dynamoDbClient()
        .updateItem(
            request ->
                request
                    .tableName(TcgInventoryTable.TABLE_NAME)
                    .key(
                        Map.of(
                            JobItem.PK, AttributeValue.fromS(JobItem.formatPk(USER)),
                            JobItem.SK, AttributeValue.fromS(JobItem.formatSk(JOB_ID))))
                    .updateExpression("SET #importId = :importId")
                    .expressionAttributeNames(Map.of("#importId", "import_id"))
                    .expressionAttributeValues(
                        Map.of(":importId", AttributeValue.fromS("historical-import-id"))));

    // act
    jobsHandler.handleRequest(event(JOB_ID, "scan_confirmation"), null);

    // assert
    var scan = factory.scanRepository().getScan(USER, SCAN_ID);
    assertThat(scan.getStatus()).isEqualTo("confirmed");
    assertThat(scan.getImportId()).isNotBlank();
    var imported =
        factory
            .importService()
            .getImport(new RequestContext(USER), new GetImportRequest(scan.getImportId()));
    assertThat(imported.status()).isEqualTo(ImportStatus.APPRAISING);
    assertThat(imported.filename()).isEqualTo(SCAN_ID + ".scan");
    assertThat(imported.rows()).hasSize(1);
    assertThat(imported.rows().getFirst().externalId()).isEqualTo(CARD_ID);
    assertThat(factory.fakeImportsQueue().getSends()).hasSize(1);
    assertThat(
            factory
                .dynamoDbClient()
                .getItem(
                    request ->
                        request
                            .tableName(TcgInventoryTable.TABLE_NAME)
                            .key(
                                Map.of(
                                    JobItem.PK, AttributeValue.fromS(JobItem.formatPk(USER)),
                                    JobItem.SK, AttributeValue.fromS(JobItem.formatSk(JOB_ID)))))
                .item())
        .containsEntry("import_id", AttributeValue.fromS("historical-import-id"));
  }

  @Test
  void handleRequestShouldPreserveCompletedJobWhenMessageIsRedelivered() throws Exception {
    // arrange
    var job = JobItem.create(USER, JOB_ID, "scan_confirmation", NOW);
    job.setStatus("succeeded");
    job.setProcessedCount(0);
    factory.jobTable().putItem(job);

    // act
    jobsHandler.handleRequest(event(JOB_ID, "scan_confirmation"), null);

    // assert
    var saved = factory.jobTable().getItem(job);
    assertThat(saved.getStatus()).isEqualTo("succeeded");
    assertThat(saved.getProcessedCount()).isZero();
    assertThat(factory.fakeImportsQueue().getSends()).isEmpty();
  }

  @Test
  void handleRequestShouldRejectRetiredJobBeforeChangingStoredRecord() throws Exception {
    // arrange
    var job = JobItem.create(USER, JOB_ID, "appraise", NOW);
    factory.jobTable().putItem(job);
    factory
        .dynamoDbClient()
        .updateItem(
            request ->
                request
                    .tableName(TcgInventoryTable.TABLE_NAME)
                    .key(
                        Map.of(
                            JobItem.PK, AttributeValue.fromS(JobItem.formatPk(USER)),
                            JobItem.SK, AttributeValue.fromS(JobItem.formatSk(JOB_ID))))
                    .updateExpression("SET #importId = :importId")
                    .expressionAttributeNames(Map.of("#importId", "import_id"))
                    .expressionAttributeValues(
                        Map.of(":importId", AttributeValue.fromS("historical-import-id"))));

    // act
    assertThatThrownBy(() -> jobsHandler.handleRequest(event(JOB_ID, "appraise"), null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("unknown job type: appraise");

    // assert
    assertThat(factory.jobTable().getItem(job).getStatus()).isEqualTo("queued");
    assertThat(
            factory
                .dynamoDbClient()
                .getItem(
                    request ->
                        request
                            .tableName(TcgInventoryTable.TABLE_NAME)
                            .key(
                                Map.of(
                                    JobItem.PK, AttributeValue.fromS(JobItem.formatPk(USER)),
                                    JobItem.SK, AttributeValue.fromS(JobItem.formatSk(JOB_ID)))))
                .item())
        .containsEntry("import_id", AttributeValue.fromS("historical-import-id"));
  }

  private SQSEvent event(String jobId, String jobType) throws Exception {
    var message = new JobMessage(USER, jobId, jobType);
    var record = new SQSEvent.SQSMessage();
    record.setBody(factory.objectMapper().writeValueAsString(message));
    var event = new SQSEvent();
    event.setRecords(List.of(record));
    return event;
  }

  private void saveConfirmingScan() {
    var scan = ScanItem.create(USER, "mtg", SCAN_ID, "NM", "normal", 1, NOW);
    scan.setStatus("confirming");
    var row = ScanRowItem.create(USER, SCAN_ID, 1, "001.jpg", 100, "unused-key");
    row.setSelectedExternalId(CARD_ID);
    factory.scanRepository().createScan(scan, List.of(row));
  }

  private static CatalogCard card() {
    return new CatalogCard(
        "mtg",
        CARD_ID,
        "Card name",
        "dom",
        "Dominaria",
        "1",
        new CatalogCard.ImageUrls(null, null),
        List.of("normal"));
  }
}
