package com.jordansimsmith.tcginventory;

import static org.assertj.core.api.Assertions.assertThat;

import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jordansimsmith.dynamodb.DynamoDbContainer;
import com.jordansimsmith.dynamodb.DynamoDbUtils;
import com.jordansimsmith.queue.FakeQueueClient;
import com.jordansimsmith.time.FakeClock;
import com.jordansimsmith.ulid.FakeUlidGenerator;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;

@Testcontainers
public class ReportsHandlerIntegrationTest {

  private FakeClock fakeClock;
  private FakeUlidGenerator fakeUlidGenerator;
  private FakeQueueClient<JobMessage> fakeJobsQueue;
  private ObjectMapper objectMapper;
  private DynamoDbTable<TcgInventoryItem> tcgInventoryTable;

  private CreateReportHandler createReportHandler;
  private GetReportsHandler getReportsHandler;
  private JobsHandler jobsHandler;

  @Container private static final DynamoDbContainer dynamoDbContainer = new DynamoDbContainer();

  @BeforeAll
  static void setUpBeforeClass() {
    var factory = TcgInventoryTestFactory.create(dynamoDbContainer.getEndpoint());
    var table = factory.tcgInventoryTable();
    DynamoDbUtils.createTable(factory.dynamoDbClient(), table);
  }

  @BeforeEach
  void setUp() {
    var factory = TcgInventoryTestFactory.create(dynamoDbContainer.getEndpoint());

    fakeClock = factory.fakeClock();
    fakeUlidGenerator = factory.fakeUlidGenerator();
    fakeJobsQueue = factory.fakeJobsQueue();
    objectMapper = factory.objectMapper();
    tcgInventoryTable = factory.tcgInventoryTable();

    DynamoDbUtils.reset(factory.dynamoDbClient());
    fakeUlidGenerator.reset();
    fakeJobsQueue.reset();

    createReportHandler = new CreateReportHandler(factory);
    getReportsHandler = new GetReportsHandler(factory);
    jobsHandler = new JobsHandler(factory);
  }

  @Test
  void getReportsShouldReturn404WhenNoReportExists() {
    // act
    var response = getReportsHandler.handleRequest(buildHttpEvent("jordan"), null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(404);
  }

  @Test
  void createReportShouldCreateJobAndSendMessage() throws Exception {
    // arrange
    fakeClock.setTime(Instant.ofEpochSecond(1700000000));

    // act
    var response = createReportHandler.handleRequest(buildHttpEvent("jordan"), null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(202);

    assertThat(fakeJobsQueue.getMessages()).hasSize(1);
    assertThat(fakeJobsQueue.getMessages().get(0).jobType()).isEqualTo("report");

    var jobId = fakeJobsQueue.getMessages().get(0).jobId();
    var jobItem =
        tcgInventoryTable.getItem(
            Key.builder()
                .partitionValue(TcgInventoryItem.formatUserPk("jordan"))
                .sortValue(TcgInventoryItem.formatJobSk(jobId))
                .build());
    assertThat(jobItem).isNotNull();
    assertThat(jobItem.getJobType()).isEqualTo("report");
    assertThat(jobItem.getStatus()).isEqualTo("queued");
  }

  @Test
  void createReportShouldBeIdempotentWhileActive() {
    // arrange
    fakeClock.setTime(Instant.ofEpochSecond(1700000000));

    var jobItem =
        TcgInventoryItem.createJob(
            "jordan", "existing-job", "report", null, Instant.ofEpochSecond(1700000000));
    jobItem.setStatus("running");
    jobItem.setProcessedCount(0);
    jobItem.setUpdatedAt(Instant.ofEpochSecond(1700000100));
    tcgInventoryTable.putItem(jobItem);

    // act
    var response = createReportHandler.handleRequest(buildHttpEvent("jordan"), null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(202);
    assertThat(fakeJobsQueue.getMessages()).isEmpty();
  }

  @Test
  void jobShouldWriteSnapshotWithAsOfAuditUlid() throws Exception {
    // arrange
    fakeClock.setTime(Instant.ofEpochSecond(1700000000));

    var auditEntry = new TcgInventoryItem();
    auditEntry.setPk(TcgInventoryItem.formatAuditPk("jordan"));
    auditEntry.setSk("01JEXAMPLEULID0000000000");
    auditEntry.setEventType("import_confirm");
    tcgInventoryTable.putItem(auditEntry);

    var jobItem =
        TcgInventoryItem.createJob(
            "jordan", "report-job", "report", null, Instant.ofEpochSecond(1700000000));
    tcgInventoryTable.putItem(jobItem);

    // act
    jobsHandler.handleRequest(buildSqsEvent("jordan", "report-job", "report"), null);

    // assert
    var reportItem =
        tcgInventoryTable.getItem(
            Key.builder()
                .partitionValue(TcgInventoryItem.formatUserPk("jordan"))
                .sortValue(TcgInventoryItem.formatReportSk())
                .build());
    assertThat(reportItem).isNotNull();
    assertThat(reportItem.getAsOfAuditUlid()).isEqualTo("01JEXAMPLEULID0000000000");
    assertThat(reportItem.getReport()).isNotNull();
    assertThat(reportItem.getUpdatedAt()).isEqualTo(Instant.ofEpochSecond(1700000000));

    var updatedJob =
        tcgInventoryTable.getItem(
            Key.builder()
                .partitionValue(TcgInventoryItem.formatUserPk("jordan"))
                .sortValue(TcgInventoryItem.formatJobSk("report-job"))
                .build());
    assertThat(updatedJob.getStatus()).isEqualTo("succeeded");
  }

  @Test
  void getReportsShouldReturnFreshWhenNoChanges() throws Exception {
    // arrange
    fakeClock.setTime(Instant.ofEpochSecond(1700000000));

    var auditEntry = new TcgInventoryItem();
    auditEntry.setPk(TcgInventoryItem.formatAuditPk("jordan"));
    auditEntry.setSk("01JEXAMPLEULID0000000000");
    auditEntry.setEventType("import_confirm");
    tcgInventoryTable.putItem(auditEntry);

    var reportItem =
        TcgInventoryItem.createReport(
            "jordan", "{}", "01JEXAMPLEULID0000000000", Instant.ofEpochSecond(1700000000));
    tcgInventoryTable.putItem(reportItem);

    var jobItem =
        TcgInventoryItem.createJob(
            "jordan", "report-job", "report", null, Instant.ofEpochSecond(1699999900));
    jobItem.setStatus("succeeded");
    jobItem.setProcessedCount(0);
    jobItem.setUpdatedAt(Instant.ofEpochSecond(1700000000));
    tcgInventoryTable.putItem(jobItem);

    // act
    var response = getReportsHandler.handleRequest(buildHttpEvent("jordan"), null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(200);
    var body = objectMapper.readTree(response.getBody());
    assertThat(body.get("stale").asBoolean()).isFalse();
    assertThat(body.get("generated_at").asLong()).isEqualTo(1700000000);
    assertThat(body.get("report")).isNotNull();
    assertThat(body.get("generation").get("status").asText()).isEqualTo("succeeded");
  }

  @Test
  void getReportsShouldReturnStaleAfterMutation() throws Exception {
    // arrange
    fakeClock.setTime(Instant.ofEpochSecond(1700000000));

    var auditEntry1 = new TcgInventoryItem();
    auditEntry1.setPk(TcgInventoryItem.formatAuditPk("jordan"));
    auditEntry1.setSk("01JEXAMPLEULID0000000000");
    auditEntry1.setEventType("import_confirm");
    tcgInventoryTable.putItem(auditEntry1);

    var reportItem =
        TcgInventoryItem.createReport(
            "jordan", "{}", "01JEXAMPLEULID0000000000", Instant.ofEpochSecond(1700000000));
    tcgInventoryTable.putItem(reportItem);

    var auditEntry2 = new TcgInventoryItem();
    auditEntry2.setPk(TcgInventoryItem.formatAuditPk("jordan"));
    auditEntry2.setSk("01JLATERULID00000000000");
    auditEntry2.setEventType("adjustment");
    tcgInventoryTable.putItem(auditEntry2);

    // act
    var response = getReportsHandler.handleRequest(buildHttpEvent("jordan"), null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(200);
    var body = objectMapper.readTree(response.getBody());
    assertThat(body.get("stale").asBoolean()).isTrue();
  }

  @Test
  void getReportsShouldReturnStaleAfter24Hours() throws Exception {
    // arrange
    var generatedAt = Instant.ofEpochSecond(1700000000);
    fakeClock.setTime(generatedAt.plus(Duration.ofHours(25)));

    var reportItem = TcgInventoryItem.createReport("jordan", "{}", null, generatedAt);
    tcgInventoryTable.putItem(reportItem);

    // act
    var response = getReportsHandler.handleRequest(buildHttpEvent("jordan"), null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(200);
    var body = objectMapper.readTree(response.getBody());
    assertThat(body.get("stale").asBoolean()).isTrue();
  }

  @Test
  void getReportsShouldIncludeGenerationStatus() throws Exception {
    // arrange
    fakeClock.setTime(Instant.ofEpochSecond(1700000000));

    var reportItem =
        TcgInventoryItem.createReport("jordan", "{}", null, Instant.ofEpochSecond(1700000000));
    tcgInventoryTable.putItem(reportItem);

    var jobItem =
        TcgInventoryItem.createJob(
            "jordan", "failed-job", "report", null, Instant.ofEpochSecond(1699999900));
    jobItem.setStatus("failed");
    jobItem.setError("out of memory");
    jobItem.setUpdatedAt(Instant.ofEpochSecond(1699999950));
    tcgInventoryTable.putItem(jobItem);

    // act
    var response = getReportsHandler.handleRequest(buildHttpEvent("jordan"), null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(200);
    var body = objectMapper.readTree(response.getBody());
    var generation = body.get("generation");
    assertThat(generation.get("status").asText()).isEqualTo("failed");
    assertThat(generation.get("error").asText()).isEqualTo("out of memory");
    assertThat(generation.get("started_at").asLong()).isEqualTo(1699999900);
    assertThat(generation.get("finished_at").asLong()).isEqualTo(1699999950);
  }

  private APIGatewayV2HTTPEvent buildHttpEvent(String user) {
    var authHeader =
        "Basic "
            + Base64.getEncoder()
                .encodeToString((user + ":password").getBytes(StandardCharsets.UTF_8));
    return APIGatewayV2HTTPEvent.builder().withHeaders(Map.of("Authorization", authHeader)).build();
  }

  private SQSEvent buildSqsEvent(String user, String jobId, String jobType) {
    try {
      var message = new JobMessage(user, jobId, jobType);
      var body = objectMapper.writeValueAsString(message);
      var sqsMessage = new SQSEvent.SQSMessage();
      sqsMessage.setBody(body);
      var event = new SQSEvent();
      event.setRecords(List.of(sqsMessage));
      return event;
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }
}
