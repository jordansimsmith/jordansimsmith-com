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
  void jobShouldComputeCorrectTotals() throws Exception {
    // arrange
    fakeClock.setTime(Instant.ofEpochSecond(1700000000));

    var sku1 =
        TcgInventoryItem.createSku(
            "jordan",
            "scryfall1#normal#NM",
            "scryfall1",
            "normal",
            "NM",
            "Lightning Bolt",
            "sta",
            "Strixhaven Mystical Archive",
            "42",
            null,
            "1.00");
    sku1.setLastPublishedPrice("1.50");
    tcgInventoryTable.putItem(sku1);

    var sku2 =
        TcgInventoryItem.createSku(
            "jordan",
            "scryfall2#normal#NM",
            "scryfall2",
            "normal",
            "NM",
            "Sol Ring",
            "cmr",
            "Commander Legends",
            "472",
            null,
            "3.00");
    tcgInventoryTable.putItem(sku2);

    var sku3 =
        TcgInventoryItem.createSku(
            "jordan",
            "scryfall3#normal#NM",
            "scryfall3",
            "normal",
            "NM",
            "Opt",
            "dom",
            "Dominaria",
            "60",
            null,
            null);
    tcgInventoryTable.putItem(sku3);

    tcgInventoryTable.putItem(
        TcgInventoryItem.createUnit(
            "jordan",
            "scryfall1#normal#NM",
            1,
            "in_stock",
            "import1",
            Instant.ofEpochSecond(1699000000)));
    tcgInventoryTable.putItem(
        TcgInventoryItem.createUnit(
            "jordan",
            "scryfall1#normal#NM",
            2,
            "in_stock",
            "import1",
            Instant.ofEpochSecond(1699000000)));
    tcgInventoryTable.putItem(
        TcgInventoryItem.createUnit(
            "jordan",
            "scryfall1#normal#NM",
            3,
            "reserved",
            "import1",
            Instant.ofEpochSecond(1699000000)));
    tcgInventoryTable.putItem(
        TcgInventoryItem.createUnit(
            "jordan",
            "scryfall2#normal#NM",
            4,
            "in_stock",
            "import1",
            Instant.ofEpochSecond(1699000000)));
    tcgInventoryTable.putItem(
        TcgInventoryItem.createUnit(
            "jordan",
            "scryfall2#normal#NM",
            5,
            "sold",
            "import1",
            Instant.ofEpochSecond(1699000000)));
    tcgInventoryTable.putItem(
        TcgInventoryItem.createUnit(
            "jordan",
            "scryfall2#normal#NM",
            6,
            "removed",
            "import1",
            Instant.ofEpochSecond(1699000000)));
    tcgInventoryTable.putItem(
        TcgInventoryItem.createUnit(
            "jordan",
            "scryfall3#normal#NM",
            7,
            "in_stock",
            "import1",
            Instant.ofEpochSecond(1699000000)));

    tcgInventoryTable.putItem(
        TcgInventoryItem.createOrder(
            "jordan",
            "order1",
            "fulfilled",
            null,
            null,
            "SHIPPING",
            "10.50",
            null,
            Instant.ofEpochSecond(1699500000)));
    tcgInventoryTable.putItem(
        TcgInventoryItem.createOrder(
            "jordan",
            "order2",
            "to_pick",
            null,
            null,
            "PICKUP",
            "5.25",
            null,
            Instant.ofEpochSecond(1699600000)));
    tcgInventoryTable.putItem(
        TcgInventoryItem.createOrder(
            "jordan",
            "order3",
            "voided",
            null,
            null,
            "PICKUP",
            "100.00",
            null,
            Instant.ofEpochSecond(1699700000)));

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

    var reportJson = objectMapper.readTree(reportItem.getReport());
    var totals = reportJson.get("totals");
    assertThat(totals).isNotNull();
    // sku1: 2 in_stock * 1.50 = 3.00, sku2: 1 in_stock * 3.00 = 3.00, sku3: unpriced
    assertThat(totals.get("inventory_value").asText()).isEqualTo("6.00");
    // sku1: 2, sku2: 1, sku3: 1
    assertThat(totals.get("in_stock_units").asInt()).isEqualTo(4);
    assertThat(totals.get("sku_count").asInt()).isEqualTo(3);
    assertThat(totals.get("reserved_units").asInt()).isEqualTo(1);
    assertThat(totals.get("sold_units").asInt()).isEqualTo(1);
    // fulfilled: 10.50, to_pick: 5.25
    assertThat(totals.get("revenue_to_date").asText()).isEqualTo("15.75");
    // sku3 has 1 in_stock unit with no price
    assertThat(totals.get("unpriced_units").asInt()).isEqualTo(1);

    var topSets = reportJson.get("top_sets");
    assertThat(topSets).isNotNull();
    assertThat(topSets.isArray()).isTrue();
    assertThat(topSets.size()).isEqualTo(3);
    // sku1 (sta): 2 in_stock, sku2 (cmr): 1 in_stock, sku3 (dom): 1 in_stock
    // ordered by count desc, tie-break by name asc: sta=2, cmr=1, dom=1
    assertThat(topSets.get(0).get("set_code").asText()).isEqualTo("sta");
    assertThat(topSets.get(0).get("set_name").asText()).isEqualTo("Strixhaven Mystical Archive");
    assertThat(topSets.get(0).get("in_stock_units").asInt()).isEqualTo(2);
    assertThat(topSets.get(1).get("set_code").asText()).isEqualTo("cmr");
    assertThat(topSets.get(1).get("set_name").asText()).isEqualTo("Commander Legends");
    assertThat(topSets.get(1).get("in_stock_units").asInt()).isEqualTo(1);
    assertThat(topSets.get(2).get("set_code").asText()).isEqualTo("dom");
    assertThat(topSets.get(2).get("set_name").asText()).isEqualTo("Dominaria");
    assertThat(topSets.get(2).get("in_stock_units").asInt()).isEqualTo(1);
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
