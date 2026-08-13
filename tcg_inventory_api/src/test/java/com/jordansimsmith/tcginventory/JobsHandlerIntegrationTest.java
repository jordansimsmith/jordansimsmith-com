package com.jordansimsmith.tcginventory;

import static org.assertj.core.api.Assertions.assertThat;

import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jordansimsmith.dynamodb.DynamoDbContainer;
import com.jordansimsmith.dynamodb.DynamoDbUtils;
import com.jordansimsmith.queue.FakeQueueClient;
import com.jordansimsmith.time.FakeClock;
import java.math.BigDecimal;
import java.time.Instant;
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
public class JobsHandlerIntegrationTest {

  private FakeClock fakeClock;
  private FakeQueueClient<JobMessage> fakeJobsQueue;
  private FakeFetchTcgClient fakeFetchTcgClient;
  private ObjectMapper objectMapper;
  private DynamoDbTable<TcgInventoryItem> tcgInventoryTable;

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
    fakeJobsQueue = factory.fakeJobsQueue();
    fakeFetchTcgClient = factory.fakeFetchTcgClient();
    objectMapper = factory.objectMapper();
    tcgInventoryTable = factory.tcgInventoryTable();

    DynamoDbUtils.reset(factory.dynamoDbClient());
    fakeJobsQueue.reset();
    fakeFetchTcgClient.reset();

    jobsHandler = new JobsHandler(factory);
  }

  @Test
  void appraiseShouldResolveIdentityAndKeep() {
    // arrange
    fakeClock.setTime(Instant.ofEpochSecond(1700000000));
    createImportWithRow("jordan", "import1", "dom", "168", "normal", "NM", "en");
    createJob("jordan", "job1", "appraise", "queued", "import1");

    fakeFetchTcgClient.seedSearchResult(
        2624,
        "168",
        new FetchTcgClient.SearchCardsResponse(
            List.of(new FetchTcgClient.SearchCard(999, "Llanowar Elves", "168"))));
    fakeFetchTcgClient.seedCard(
        999,
        new FetchTcgClient.GetCardResponse(
            999,
            "Llanowar Elves",
            Map.of("NZ", new FetchTcgClient.PricingData(new BigDecimal("1.50")))));
    fakeFetchTcgClient.seedListings(
        999,
        new FetchTcgClient.GetCardListingsResponse(
            List.of(
                new FetchTcgClient.CardListing(
                    1, "raw-nm", new BigDecimal("1.20"), "rival1", 50))));

    // act
    jobsHandler.handleRequest(buildSqsEvent("jordan", "job1", "appraise"), null);

    // assert
    var row = getRow("jordan", "import1", 1);
    assertThat(row.getDecision()).isEqualTo("keep");
    assertThat(row.getMarketPrice()).isEqualTo("1.50");
    assertThat(row.getSuggestedPrice()).isNotNull();

    var importItem = getImport("jordan", "import1");
    assertThat(importItem.getStatus()).isEqualTo("review");
    assertThat(importItem.getKeepCount()).isEqualTo(1);
  }

  @Test
  void appraiseShouldDiscardBelowThreshold() {
    // arrange
    fakeClock.setTime(Instant.ofEpochSecond(1700000000));
    createImportWithRow("jordan", "import1", "dom", "168", "normal", "NM", "en");
    createJob("jordan", "job1", "appraise", "queued", "import1");

    fakeFetchTcgClient.seedSearchResult(
        2624,
        "168",
        new FetchTcgClient.SearchCardsResponse(
            List.of(new FetchTcgClient.SearchCard(999, "Llanowar Elves", "168"))));
    fakeFetchTcgClient.seedCard(
        999,
        new FetchTcgClient.GetCardResponse(
            999,
            "Llanowar Elves",
            Map.of("NZ", new FetchTcgClient.PricingData(new BigDecimal("0.10")))));

    // act
    jobsHandler.handleRequest(buildSqsEvent("jordan", "job1", "appraise"), null);

    // assert
    var row = getRow("jordan", "import1", 1);
    assertThat(row.getDecision()).isEqualTo("discard");
    assertThat(row.getDecisionReason()).isEqualTo("below threshold");
    assertThat(row.getMarketPrice()).isEqualTo("0.10");

    var importItem = getImport("jordan", "import1");
    assertThat(importItem.getDiscardCount()).isEqualTo(1);
  }

  @Test
  void appraiseShouldReviewNonEnglish() {
    // arrange
    fakeClock.setTime(Instant.ofEpochSecond(1700000000));
    createImportWithRow("jordan", "import1", "dom", "168", "normal", "NM", "ja");
    createJob("jordan", "job1", "appraise", "queued", "import1");

    // act
    jobsHandler.handleRequest(buildSqsEvent("jordan", "job1", "appraise"), null);

    // assert
    var row = getRow("jordan", "import1", 1);
    assertThat(row.getDecision()).isEqualTo("review");
    assertThat(row.getDecisionReason()).isEqualTo("non-english");

    var importItem = getImport("jordan", "import1");
    assertThat(importItem.getReviewCount()).isEqualTo(1);
  }

  @Test
  void appraiseShouldReviewUnmappedSet() {
    // arrange
    fakeClock.setTime(Instant.ofEpochSecond(1700000000));
    createImportWithRow("jordan", "import1", "zzz_unmapped", "1", "normal", "NM", "en");
    createJob("jordan", "job1", "appraise", "queued", "import1");

    // act
    jobsHandler.handleRequest(buildSqsEvent("jordan", "job1", "appraise"), null);

    // assert
    var row = getRow("jordan", "import1", 1);
    assertThat(row.getDecision()).isEqualTo("review");
    assertThat(row.getDecisionReason()).isEqualTo("unmapped set");

    var importItem = getImport("jordan", "import1");
    assertThat(importItem.getReviewCount()).isEqualTo(1);
  }

  @Test
  void appraiseShouldReviewUnresolvable() {
    // arrange
    fakeClock.setTime(Instant.ofEpochSecond(1700000000));
    createImportWithRow("jordan", "import1", "dom", "999", "normal", "NM", "en");
    createJob("jordan", "job1", "appraise", "queued", "import1");

    // act
    jobsHandler.handleRequest(buildSqsEvent("jordan", "job1", "appraise"), null);

    // assert
    var row = getRow("jordan", "import1", 1);
    assertThat(row.getDecision()).isEqualTo("review");
    assertThat(row.getDecisionReason()).isEqualTo("unresolvable");

    var importItem = getImport("jordan", "import1");
    assertThat(importItem.getReviewCount()).isEqualTo(1);
  }

  @Test
  void appraiseShouldDedupeWithinBatch() {
    // arrange
    fakeClock.setTime(Instant.ofEpochSecond(1700000000));
    createImportWithRows(
        "jordan",
        "import1",
        List.of(
            new RowSpec("dom", "168", "normal", "NM", "en", "scryfall-1"),
            new RowSpec("dom", "168", "normal", "LP", "en", "scryfall-1")));
    createJob("jordan", "job1", "appraise", "queued", "import1");

    fakeFetchTcgClient.seedSearchResult(
        2624,
        "168",
        new FetchTcgClient.SearchCardsResponse(
            List.of(new FetchTcgClient.SearchCard(999, "Llanowar Elves", "168"))));
    fakeFetchTcgClient.seedCard(
        999,
        new FetchTcgClient.GetCardResponse(
            999,
            "Llanowar Elves",
            Map.of("NZ", new FetchTcgClient.PricingData(new BigDecimal("1.50")))));

    // act
    jobsHandler.handleRequest(buildSqsEvent("jordan", "job1", "appraise"), null);

    // assert
    assertThat(fakeFetchTcgClient.getSearchCallCount()).isEqualTo(1);

    var row1 = getRow("jordan", "import1", 1);
    assertThat(row1.getDecision()).isEqualTo("keep");
    var row2 = getRow("jordan", "import1", 2);
    assertThat(row2.getDecision()).isEqualTo("keep");
  }

  @Test
  void appraiseShouldCheckpointAndContinue() {
    // arrange
    fakeClock.setTime(Instant.ofEpochSecond(1700000000));
    int totalRows = AppraiseJobProcessor.BATCH_SIZE + 2;
    createImportWithNRows("jordan", "import1", totalRows);
    createJob("jordan", "job1", "appraise", "queued", "import1");

    seedDefaultCardForDom168();

    // act
    jobsHandler.handleRequest(buildSqsEvent("jordan", "job1", "appraise"), null);

    // assert
    var jobItem = getJob("jordan", "job1");
    assertThat(jobItem.getStatus()).isEqualTo("running");
    assertThat(jobItem.getContinuation()).isEqualTo(AppraiseJobProcessor.BATCH_SIZE);
    assertThat(fakeJobsQueue.getMessages()).hasSize(1);

    // act - second batch
    fakeJobsQueue.reset();
    jobsHandler.handleRequest(buildSqsEvent("jordan", "job1", "appraise"), null);

    // assert
    var completedJob = getJob("jordan", "job1");
    assertThat(completedJob.getStatus()).isEqualTo("succeeded");
    assertThat(completedJob.getProcessedCount()).isEqualTo(totalRows);
    assertThat(fakeJobsQueue.getMessages()).isEmpty();

    var importItem = getImport("jordan", "import1");
    assertThat(importItem.getStatus()).isEqualTo("review");
  }

  @Test
  void duplicateDeliveryShouldNoOpWhenSucceeded() {
    // arrange
    fakeClock.setTime(Instant.ofEpochSecond(1700000000));

    var jobItem = new TcgInventoryItem();
    jobItem.setPk(TcgInventoryItem.formatUserPk("jordan"));
    jobItem.setSk(TcgInventoryItem.formatJobSk("job1"));
    jobItem.setJobId("job1");
    jobItem.setJobType("appraise");
    jobItem.setStatus("succeeded");
    jobItem.setImportId("import1");
    jobItem.setProcessedCount(5);
    jobItem.setContinuation(5);
    jobItem.setCreatedAt(Instant.ofEpochSecond(1700000000));
    jobItem.setUpdatedAt(Instant.ofEpochSecond(1700000100));
    tcgInventoryTable.putItem(jobItem);

    // act
    jobsHandler.handleRequest(buildSqsEvent("jordan", "job1", "appraise"), null);

    // assert
    var updatedJob = getJob("jordan", "job1");
    assertThat(updatedJob.getStatus()).isEqualTo("succeeded");
    assertThat(updatedJob.getUpdatedAt()).isEqualTo(Instant.ofEpochSecond(1700000100));
    assertThat(fakeJobsQueue.getMessages()).isEmpty();
  }

  @Test
  void publishJobShouldCompleteImmediately() {
    // arrange
    fakeClock.setTime(Instant.ofEpochSecond(1700000000));

    var jobItem = new TcgInventoryItem();
    jobItem.setPk(TcgInventoryItem.formatUserPk("jordan"));
    jobItem.setSk(TcgInventoryItem.formatJobSk("job1"));
    jobItem.setJobId("job1");
    jobItem.setJobType("publish");
    jobItem.setStatus("queued");
    jobItem.setCreatedAt(Instant.ofEpochSecond(1700000000));
    tcgInventoryTable.putItem(jobItem);

    // act
    jobsHandler.handleRequest(buildSqsEvent("jordan", "job1", "publish"), null);

    // assert
    var updatedJob = getJob("jordan", "job1");
    assertThat(updatedJob.getStatus()).isEqualTo("succeeded");
    assertThat(fakeJobsQueue.getMessages()).isEmpty();
  }

  private void seedDefaultCardForDom168() {
    fakeFetchTcgClient.seedSearchResult(
        2624,
        "168",
        new FetchTcgClient.SearchCardsResponse(
            List.of(new FetchTcgClient.SearchCard(999, "Llanowar Elves", "168"))));
    fakeFetchTcgClient.seedCard(
        999,
        new FetchTcgClient.GetCardResponse(
            999,
            "Llanowar Elves",
            Map.of("NZ", new FetchTcgClient.PricingData(new BigDecimal("1.50")))));
  }

  private void createImportWithRow(
      String user,
      String importId,
      String setCode,
      String collectorNumber,
      String finish,
      String condition,
      String language) {
    createImportWithRows(
        user,
        importId,
        List.of(new RowSpec(setCode, collectorNumber, finish, condition, language, "scryfall-1")));
  }

  private void createImportWithRows(String user, String importId, List<RowSpec> rows) {
    var importItem = new TcgInventoryItem();
    importItem.setPk(TcgInventoryItem.formatUserPk(user));
    importItem.setSk(TcgInventoryItem.formatImportSk(importId));
    importItem.setImportId(importId);
    importItem.setFilename("test.csv");
    importItem.setStatus("appraising");
    importItem.setRowCount(rows.size());
    importItem.setKeepCount(0);
    importItem.setDiscardCount(0);
    importItem.setReviewCount(0);
    importItem.setCreatedAt(Instant.ofEpochSecond(1700000000));
    tcgInventoryTable.putItem(importItem);

    for (int i = 0; i < rows.size(); i++) {
      var spec = rows.get(i);
      var rowItem = new TcgInventoryItem();
      rowItem.setPk(TcgInventoryItem.formatImportRowPk(user, importId));
      rowItem.setSk(TcgInventoryItem.formatImportRowSk(i + 1));
      rowItem.setPosition(i + 1);
      rowItem.setName("Card " + (i + 1));
      rowItem.setSetCode(spec.setCode());
      rowItem.setSetName("Test Set");
      rowItem.setCollectorNumber(spec.collectorNumber());
      rowItem.setFinish(spec.finish());
      rowItem.setCondition(spec.condition());
      rowItem.setScryfallId(spec.scryfallId());
      rowItem.setLanguage(spec.language());
      tcgInventoryTable.putItem(rowItem);
    }
  }

  private void createImportWithNRows(String user, String importId, int rowCount) {
    var importItem = new TcgInventoryItem();
    importItem.setPk(TcgInventoryItem.formatUserPk(user));
    importItem.setSk(TcgInventoryItem.formatImportSk(importId));
    importItem.setImportId(importId);
    importItem.setFilename("test.csv");
    importItem.setStatus("appraising");
    importItem.setRowCount(rowCount);
    importItem.setKeepCount(0);
    importItem.setDiscardCount(0);
    importItem.setReviewCount(0);
    importItem.setCreatedAt(Instant.ofEpochSecond(1700000000));
    tcgInventoryTable.putItem(importItem);

    for (int i = 1; i <= rowCount; i++) {
      var rowItem = new TcgInventoryItem();
      rowItem.setPk(TcgInventoryItem.formatImportRowPk(user, importId));
      rowItem.setSk(TcgInventoryItem.formatImportRowSk(i));
      rowItem.setPosition(i);
      rowItem.setName("Card " + i);
      rowItem.setSetCode("dom");
      rowItem.setSetName("Dominaria");
      rowItem.setCollectorNumber("168");
      rowItem.setFinish("normal");
      rowItem.setCondition("NM");
      rowItem.setScryfallId("scryfall-" + i);
      rowItem.setLanguage("en");
      tcgInventoryTable.putItem(rowItem);
    }
  }

  private TcgInventoryItem createJob(
      String user, String jobId, String jobType, String status, String importId) {
    var jobItem = new TcgInventoryItem();
    jobItem.setPk(TcgInventoryItem.formatUserPk(user));
    jobItem.setSk(TcgInventoryItem.formatJobSk(jobId));
    jobItem.setJobId(jobId);
    jobItem.setJobType(jobType);
    jobItem.setStatus(status);
    jobItem.setImportId(importId);
    jobItem.setCreatedAt(Instant.ofEpochSecond(1700000000));
    tcgInventoryTable.putItem(jobItem);
    return jobItem;
  }

  private TcgInventoryItem getRow(String user, String importId, int position) {
    return tcgInventoryTable.getItem(
        Key.builder()
            .partitionValue(TcgInventoryItem.formatImportRowPk(user, importId))
            .sortValue(TcgInventoryItem.formatImportRowSk(position))
            .build());
  }

  private TcgInventoryItem getImport(String user, String importId) {
    return tcgInventoryTable.getItem(
        Key.builder()
            .partitionValue(TcgInventoryItem.formatUserPk(user))
            .sortValue(TcgInventoryItem.formatImportSk(importId))
            .build());
  }

  private TcgInventoryItem getJob(String user, String jobId) {
    return tcgInventoryTable.getItem(
        Key.builder()
            .partitionValue(TcgInventoryItem.formatUserPk(user))
            .sortValue(TcgInventoryItem.formatJobSk(jobId))
            .build());
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

  record RowSpec(
      String setCode,
      String collectorNumber,
      String finish,
      String condition,
      String language,
      String scryfallId) {}
}
