package com.jordansimsmith.tcginventory.reports;

import static org.assertj.core.api.Assertions.assertThat;

import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jordansimsmith.dynamodb.DynamoDbContainer;
import com.jordansimsmith.dynamodb.DynamoDbUtils;
import com.jordansimsmith.queue.FakeQueueClient;
import com.jordansimsmith.tcginventory.AuditItem;
import com.jordansimsmith.tcginventory.FakeFetchTcgClient;
import com.jordansimsmith.tcginventory.FetchTcgClient;
import com.jordansimsmith.tcginventory.Games;
import com.jordansimsmith.tcginventory.JobItem;
import com.jordansimsmith.tcginventory.JobMessage;
import com.jordansimsmith.tcginventory.JobsHandler;
import com.jordansimsmith.tcginventory.TcgInventoryTestFactory;
import com.jordansimsmith.tcginventory.inventory.SkuItem;
import com.jordansimsmith.tcginventory.inventory.UnitItem;
import com.jordansimsmith.tcginventory.orders.OrderItem;
import com.jordansimsmith.time.FakeClock;
import com.jordansimsmith.ulid.FakeUlidGenerator;
import java.math.BigDecimal;
import java.net.URI;
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
  private FakeFetchTcgClient fakeFetchTcgClient;
  private ObjectMapper objectMapper;
  private DynamoDbTable<JobItem> jobTable;
  private DynamoDbTable<ReportItem> reportTable;
  private DynamoDbTable<AuditItem> auditTable;
  private DynamoDbTable<SkuItem> skuTable;
  private DynamoDbTable<UnitItem> unitTable;
  private DynamoDbTable<OrderItem> orderTable;

  private CreateReportHandler createReportHandler;
  private GetReportsHandler getReportsHandler;
  private JobsHandler jobsHandler;

  @Container private static final DynamoDbContainer dynamoDbContainer = new DynamoDbContainer();

  private static final URI UNUSED_S3_ENDPOINT = URI.create("http://localhost:1");

  @BeforeAll
  static void setUpBeforeClass() {
    var factory =
        TcgInventoryTestFactory.create(dynamoDbContainer.getEndpoint(), UNUSED_S3_ENDPOINT);
    var table = factory.tableDefinition();
    DynamoDbUtils.createTable(factory.dynamoDbClient(), table);
  }

  @BeforeEach
  void setUp() {
    var factory =
        TcgInventoryTestFactory.create(dynamoDbContainer.getEndpoint(), UNUSED_S3_ENDPOINT);

    fakeClock = factory.fakeClock();
    fakeUlidGenerator = factory.fakeUlidGenerator();
    fakeJobsQueue = factory.fakeJobsQueue();
    fakeFetchTcgClient = factory.fakeFetchTcgClient();
    objectMapper = factory.objectMapper();
    jobTable = factory.jobTable();
    reportTable = factory.reportTable();
    auditTable = factory.auditTable();
    skuTable = factory.skuTable();
    unitTable = factory.unitTable();
    orderTable = factory.orderTable();

    DynamoDbUtils.reset(factory.dynamoDbClient());
    fakeUlidGenerator.reset();
    fakeJobsQueue.reset();
    fakeFetchTcgClient.reset();

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

    assertThat(fakeJobsQueue.getSends()).hasSize(1);
    var send = fakeJobsQueue.getSends().get(0);
    assertThat(send.message().jobType()).isEqualTo("report");
    assertThat(send.messageGroupId()).isEqualTo("jordan");

    var jobId = send.message().jobId();
    assertThat(send.messageDeduplicationId()).isEqualTo(jobId + "#0");
    var jobItem =
        jobTable.getItem(
            Key.builder()
                .partitionValue(SkuItem.formatUserPk("jordan"))
                .sortValue(JobItem.formatSk(jobId))
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
        JobItem.create("jordan", "existing-job", "report", null, Instant.ofEpochSecond(1700000000));
    jobItem.setStatus("running");
    jobItem.setProcessedCount(0);
    jobItem.setUpdatedAt(Instant.ofEpochSecond(1700000100));
    jobTable.putItem(jobItem);

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

    var auditEntry = new AuditItem();
    auditEntry.setPk(AuditItem.formatPk("jordan"));
    auditEntry.setSk("01JEXAMPLEULID0000000000");
    auditEntry.setEventType("import_confirm");
    auditTable.putItem(auditEntry);

    var jobItem =
        JobItem.create("jordan", "report-job", "report", null, Instant.ofEpochSecond(1700000000));
    jobTable.putItem(jobItem);

    // act
    jobsHandler.handleRequest(buildSqsEvent("jordan", "report-job", "report"), null);

    // assert
    var reportItem =
        reportTable.getItem(
            Key.builder()
                .partitionValue(SkuItem.formatUserPk("jordan"))
                .sortValue(ReportItem.formatSk())
                .build());
    assertThat(reportItem).isNotNull();
    assertThat(reportItem.getAsOfAuditUlid()).isEqualTo("01JEXAMPLEULID0000000000");
    assertThat(reportItem.getReport()).isNotNull();
    assertThat(reportItem.getUpdatedAt()).isEqualTo(Instant.ofEpochSecond(1700000000));

    var updatedJob =
        jobTable.getItem(
            Key.builder()
                .partitionValue(SkuItem.formatUserPk("jordan"))
                .sortValue(JobItem.formatSk("report-job"))
                .build());
    assertThat(updatedJob.getStatus()).isEqualTo("succeeded");
  }

  @Test
  void jobShouldComputeCorrectTotals() throws Exception {
    // arrange
    fakeClock.setTime(Instant.ofEpochSecond(1700000000));

    var sku1 =
        SkuItem.create(
            "jordan",
            "mtg#scryfall#scryfall1#normal#NM",
            Games.MAGIC_THE_GATHERING.id(),
            Games.MAGIC_THE_GATHERING.externalSource(),
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
    skuTable.putItem(sku1);

    var sku2 =
        SkuItem.create(
            "jordan",
            "mtg#scryfall#scryfall2#normal#NM",
            Games.MAGIC_THE_GATHERING.id(),
            Games.MAGIC_THE_GATHERING.externalSource(),
            "scryfall2",
            "normal",
            "NM",
            "Sol Ring",
            "cmr",
            "Commander Legends",
            "472",
            null,
            "3.00");
    skuTable.putItem(sku2);

    var sku3 =
        SkuItem.create(
            "jordan",
            "mtg#scryfall#scryfall3#normal#NM",
            Games.MAGIC_THE_GATHERING.id(),
            Games.MAGIC_THE_GATHERING.externalSource(),
            "scryfall3",
            "normal",
            "NM",
            "Opt",
            "dom",
            "Dominaria",
            "60",
            null,
            null);
    skuTable.putItem(sku3);

    unitTable.putItem(
        UnitItem.create(
            "jordan",
            "mtg",
            "mtg#scryfall#scryfall1#normal#NM",
            1,
            "in_stock",
            "import1",
            Instant.ofEpochSecond(1699000000)));
    unitTable.putItem(
        UnitItem.create(
            "jordan",
            "mtg",
            "mtg#scryfall#scryfall1#normal#NM",
            2,
            "in_stock",
            "import1",
            Instant.ofEpochSecond(1699000000)));
    unitTable.putItem(
        UnitItem.create(
            "jordan",
            "mtg",
            "mtg#scryfall#scryfall1#normal#NM",
            3,
            "reserved",
            "import1",
            Instant.ofEpochSecond(1699000000)));
    unitTable.putItem(
        UnitItem.create(
            "jordan",
            "mtg",
            "mtg#scryfall#scryfall2#normal#NM",
            4,
            "in_stock",
            "import1",
            Instant.ofEpochSecond(1699000000)));
    var soldUnit =
        UnitItem.create(
            "jordan",
            "mtg",
            "mtg#scryfall#scryfall2#normal#NM",
            5,
            "sold",
            "import1",
            Instant.ofEpochSecond(1699000000));
    soldUnit.setUpdatedAt(Instant.ofEpochSecond(1699500000));
    unitTable.putItem(soldUnit);
    unitTable.putItem(
        UnitItem.create(
            "jordan",
            "mtg",
            "mtg#scryfall#scryfall2#normal#NM",
            6,
            "removed",
            "import1",
            Instant.ofEpochSecond(1699000000)));
    unitTable.putItem(
        UnitItem.create(
            "jordan",
            "mtg",
            "mtg#scryfall#scryfall3#normal#NM",
            7,
            "in_stock",
            "import1",
            Instant.ofEpochSecond(1699000000)));

    orderTable.putItem(
        OrderItem.create(
            "jordan",
            "1",
            "fulfilled",
            null,
            null,
            "SHIPPING",
            null,
            null,
            null,
            "10.50",
            "[{\"sku_id\":\"s\",\"fetchtcg_listing_id\":1,\"quantity\":1,\"price\":\"10.50\",\"allocated_sequence_numbers\":[]}]",
            Instant.ofEpochSecond(1699500000)));
    orderTable.putItem(
        OrderItem.create(
            "jordan",
            "2",
            "to_pick",
            null,
            null,
            "PICKUP",
            null,
            null,
            null,
            "5.25",
            "[{\"sku_id\":\"s\",\"fetchtcg_listing_id\":1,\"quantity\":1,\"price\":\"5.25\",\"allocated_sequence_numbers\":[]}]",
            Instant.ofEpochSecond(1699600000)));
    orderTable.putItem(
        OrderItem.create(
            "jordan",
            "3",
            "voided",
            null,
            null,
            "PICKUP",
            null,
            null,
            null,
            "100.00",
            "[{\"sku_id\":\"s\",\"fetchtcg_listing_id\":1,\"quantity\":1,\"price\":\"100.00\",\"allocated_sequence_numbers\":[]}]",
            Instant.ofEpochSecond(1699700000)));

    var jobItem =
        JobItem.create("jordan", "report-job", "report", null, Instant.ofEpochSecond(1700000000));
    jobTable.putItem(jobItem);

    // act
    jobsHandler.handleRequest(buildSqsEvent("jordan", "report-job", "report"), null);

    // assert
    var reportItem =
        reportTable.getItem(
            Key.builder()
                .partitionValue(SkuItem.formatUserPk("jordan"))
                .sortValue(ReportItem.formatSk())
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

    var priceBuckets = reportJson.get("price_buckets");
    assertThat(priceBuckets).isNotNull();
    assertThat(priceBuckets.isArray()).isTrue();
    assertThat(priceBuckets.size()).isEqualTo(6);
    assertThat(priceBuckets.get(0).get("label").asText()).isEqualTo("$0.25-$0.50");
    assertThat(priceBuckets.get(5).get("label").asText()).isEqualTo("$10+");
    // sku1: 2 in_stock at $1.50 -> bucket "1-2", sku2: 1 in_stock at $3.00 -> bucket "2-5"
    assertThat(priceBuckets.get(2).get("in_stock_units").asInt()).isEqualTo(2);
    assertThat(priceBuckets.get(3).get("in_stock_units").asInt()).isEqualTo(1);

    var topHits = reportJson.get("top_hits");
    assertThat(topHits).isNotNull();
    assertThat(topHits.isArray()).isTrue();
    // sku2 (Sol Ring, $3.00) first, sku1 (Lightning Bolt, $1.50) second; sku3 unpriced excluded
    assertThat(topHits.size()).isEqualTo(2);
    assertThat(topHits.get(0).get("name").asText()).isEqualTo("Sol Ring");
    assertThat(topHits.get(0).get("price").asText()).isEqualTo("3.00");
    assertThat(topHits.get(0).get("in_stock_units").asInt()).isEqualTo(1);
    assertThat(topHits.get(1).get("name").asText()).isEqualTo("Lightning Bolt");
    assertThat(topHits.get(1).get("price").asText()).isEqualTo("1.50");
    assertThat(topHits.get(1).get("in_stock_units").asInt()).isEqualTo(2);

    var agingBands = reportJson.get("aging_bands");
    assertThat(agingBands).isNotNull();
    assertThat(agingBands.isArray()).isTrue();
    assertThat(agingBands.size()).isEqualTo(4);
    assertThat(agingBands.get(0).get("label").asText()).isEqualTo("0-30 days");
    assertThat(agingBands.get(1).get("label").asText()).isEqualTo("31-90 days");
    assertThat(agingBands.get(2).get("label").asText()).isEqualTo("91-180 days");
    assertThat(agingBands.get(3).get("label").asText()).isEqualTo("180+ days");
    // all units created at 1699000000, generation at 1700000000 -> ~11.5 days -> band 0-30
    assertThat(agingBands.get(0).get("in_stock_units").asInt()).isEqualTo(4);
    assertThat(agingBands.get(1).get("in_stock_units").asInt()).isEqualTo(0);
    assertThat(agingBands.get(2).get("in_stock_units").asInt()).isEqualTo(0);
    assertThat(agingBands.get(3).get("in_stock_units").asInt()).isEqualTo(0);

    var revenueByMonth = reportJson.get("revenue_by_month");
    assertThat(revenueByMonth).isNotNull();
    assertThat(revenueByMonth.isArray()).isTrue();
    // orders 1 and 2 were created in November 2023 NZ time
    assertThat(revenueByMonth.size()).isEqualTo(1);
    assertThat(revenueByMonth.get(0).get("month").asText()).isEqualTo("2023-11");
    assertThat(revenueByMonth.get(0).get("revenue").asText()).isEqualTo("15.75");
    assertThat(revenueByMonth.get(0).get("order_count").asInt()).isEqualTo(2);

    var intakeVsSales = reportJson.get("intake_vs_sales_by_week");
    assertThat(intakeVsSales).isNotNull();
    assertThat(intakeVsSales.isArray()).isTrue();
    // all non-removed units created at 1699000000 (2023-11-03 NZ, Friday) -> week 2023-10-30
    // sold unit updatedAt 1699500000 (2023-11-09 NZ, Thursday) -> week 2023-11-06
    assertThat(intakeVsSales.size()).isEqualTo(2);
    assertThat(intakeVsSales.get(0).get("week_start").asText()).isEqualTo("2023-10-30");
    assertThat(intakeVsSales.get(0).get("added_units").asInt()).isEqualTo(6);
    assertThat(intakeVsSales.get(0).get("sold_units").asInt()).isEqualTo(0);
    assertThat(intakeVsSales.get(1).get("week_start").asText()).isEqualTo("2023-11-06");
    assertThat(intakeVsSales.get(1).get("added_units").asInt()).isEqualTo(0);
    assertThat(intakeVsSales.get(1).get("sold_units").asInt()).isEqualTo(1);
  }

  @Test
  void getReportsShouldReturnFreshWhenNoChanges() throws Exception {
    // arrange
    fakeClock.setTime(Instant.ofEpochSecond(1700000000));

    var auditEntry = new AuditItem();
    auditEntry.setPk(AuditItem.formatPk("jordan"));
    auditEntry.setSk("01JEXAMPLEULID0000000000");
    auditEntry.setEventType("import_confirm");
    auditTable.putItem(auditEntry);

    var reportItem =
        ReportItem.create(
            "jordan", "{}", "01JEXAMPLEULID0000000000", Instant.ofEpochSecond(1700000000));
    reportTable.putItem(reportItem);

    var jobItem =
        JobItem.create("jordan", "report-job", "report", null, Instant.ofEpochSecond(1699999900));
    jobItem.setStatus("succeeded");
    jobItem.setProcessedCount(0);
    jobItem.setUpdatedAt(Instant.ofEpochSecond(1700000000));
    jobTable.putItem(jobItem);

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

    var auditEntry1 = new AuditItem();
    auditEntry1.setPk(AuditItem.formatPk("jordan"));
    auditEntry1.setSk("01JEXAMPLEULID0000000000");
    auditEntry1.setEventType("import_confirm");
    auditTable.putItem(auditEntry1);

    var reportItem =
        ReportItem.create(
            "jordan", "{}", "01JEXAMPLEULID0000000000", Instant.ofEpochSecond(1700000000));
    reportTable.putItem(reportItem);

    var auditEntry2 = new AuditItem();
    auditEntry2.setPk(AuditItem.formatPk("jordan"));
    auditEntry2.setSk("01JLATERULID00000000000");
    auditEntry2.setEventType("adjustment");
    auditTable.putItem(auditEntry2);

    // act
    var response = getReportsHandler.handleRequest(buildHttpEvent("jordan"), null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(200);
    var body = objectMapper.readTree(response.getBody());
    assertThat(body.get("stale").asBoolean()).isTrue();
  }

  @Test
  void getReportsShouldReturnStaleAfterOrderAdvance() throws Exception {
    // arrange
    fakeClock.setTime(Instant.ofEpochSecond(1700000000));

    var auditEntry = new AuditItem();
    auditEntry.setPk(AuditItem.formatPk("jordan"));
    auditEntry.setSk("01JEXAMPLEULID0000000000");
    auditEntry.setEventType("reserve");
    auditTable.putItem(auditEntry);

    var reportItem =
        ReportItem.create(
            "jordan", "{}", "01JEXAMPLEULID0000000000", Instant.ofEpochSecond(1700000000));
    reportTable.putItem(reportItem);

    var order =
        OrderItem.create(
            "jordan",
            "83663",
            "awaiting_payment",
            "ACCEPTED",
            null,
            "PICKUP",
            null,
            null,
            null,
            "3.33",
            "[]",
            Instant.ofEpochSecond(1699000000));
    orderTable.putItem(order);

    fakeFetchTcgClient.seedSellerOffers(
        List.of(
            new FetchTcgClient.SellerOffer(
                83663,
                "ACCEPTED",
                "SEND_PICKUP_ADDRESS",
                "2026-08-11T04:42:12.476+0000",
                "PICKUP",
                null,
                null,
                null,
                new BigDecimal("3.33"),
                List.of())));

    jobTable.putItem(
        JobItem.create(
            "jordan", "publish-job", "publish", null, Instant.ofEpochSecond(1700000000)));

    // act
    jobsHandler.handleRequest(buildSqsEvent("jordan", "publish-job", "publish"), null);
    var response = getReportsHandler.handleRequest(buildHttpEvent("jordan"), null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(200);
    var body = objectMapper.readTree(response.getBody());
    assertThat(body.get("stale").asBoolean()).isTrue();

    var updatedOrder =
        orderTable.getItem(
            Key.builder()
                .partitionValue(SkuItem.formatUserPk("jordan"))
                .sortValue(OrderItem.formatSk("83663"))
                .build());
    assertThat(updatedOrder.getStatus()).isEqualTo("to_pick");
  }

  @Test
  void getReportsShouldReturnStaleAfter24Hours() throws Exception {
    // arrange
    var generatedAt = Instant.ofEpochSecond(1700000000);
    fakeClock.setTime(generatedAt.plus(Duration.ofHours(25)));

    var reportItem = ReportItem.create("jordan", "{}", null, generatedAt);
    reportTable.putItem(reportItem);

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

    var reportItem = ReportItem.create("jordan", "{}", null, Instant.ofEpochSecond(1700000000));
    reportTable.putItem(reportItem);

    var jobItem =
        JobItem.create("jordan", "failed-job", "report", null, Instant.ofEpochSecond(1699999900));
    jobItem.setStatus("failed");
    jobItem.setError("out of memory");
    jobItem.setUpdatedAt(Instant.ofEpochSecond(1699999950));
    jobTable.putItem(jobItem);

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
