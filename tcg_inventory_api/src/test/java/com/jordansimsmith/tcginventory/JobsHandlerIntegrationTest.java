package com.jordansimsmith.tcginventory;

import static org.assertj.core.api.Assertions.assertThat;

import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jordansimsmith.dynamodb.DynamoDbContainer;
import com.jordansimsmith.dynamodb.DynamoDbUtils;
import com.jordansimsmith.queue.FakeQueueClient;
import com.jordansimsmith.time.FakeClock;
import java.time.Instant;
import java.util.List;
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
    objectMapper = factory.objectMapper();
    tcgInventoryTable = factory.tcgInventoryTable();

    DynamoDbUtils.reset(factory.dynamoDbClient());
    fakeJobsQueue.reset();

    jobsHandler = new JobsHandler(factory);
  }

  @Test
  void triggerShouldTransitionJobToRunningAndComplete() {
    // arrange
    fakeClock.setTime(Instant.ofEpochSecond(1700000000));

    createImportWithRows("jordan", "import1", 3);
    createJob("jordan", "job1", "appraise", "queued", "import1");

    // act
    jobsHandler.handleRequest(buildSqsEvent("jordan", "job1", "appraise"), null);

    // assert
    var updatedJob =
        tcgInventoryTable.getItem(
            Key.builder()
                .partitionValue(TcgInventoryItem.formatUserPk("jordan"))
                .sortValue(TcgInventoryItem.formatJobSk("job1"))
                .build());
    assertThat(updatedJob.getStatus()).isEqualTo("succeeded");
    assertThat(updatedJob.getProcessedCount()).isEqualTo(3);
    assertThat(updatedJob.getContinuation()).isEqualTo(3);

    var updatedImport =
        tcgInventoryTable.getItem(
            Key.builder()
                .partitionValue(TcgInventoryItem.formatUserPk("jordan"))
                .sortValue(TcgInventoryItem.formatImportSk("import1"))
                .build());
    assertThat(updatedImport.getStatus()).isEqualTo("review");
    assertThat(updatedImport.getKeepCount()).isEqualTo(3);

    assertThat(fakeJobsQueue.getMessages()).isEmpty();
  }

  @Test
  void batchShouldCheckpointContinuationAndReenqueue() {
    // arrange
    fakeClock.setTime(Instant.ofEpochSecond(1700000000));

    createImportWithRows("jordan", "import1", AppraiseJobProcessor.BATCH_SIZE + 5);
    createJob("jordan", "job1", "appraise", "queued", "import1");

    // act
    jobsHandler.handleRequest(buildSqsEvent("jordan", "job1", "appraise"), null);

    // assert
    var updatedJob =
        tcgInventoryTable.getItem(
            Key.builder()
                .partitionValue(TcgInventoryItem.formatUserPk("jordan"))
                .sortValue(TcgInventoryItem.formatJobSk("job1"))
                .build());
    assertThat(updatedJob.getStatus()).isEqualTo("running");
    assertThat(updatedJob.getContinuation()).isEqualTo(AppraiseJobProcessor.BATCH_SIZE);
    assertThat(updatedJob.getProcessedCount()).isEqualTo(AppraiseJobProcessor.BATCH_SIZE);

    assertThat(fakeJobsQueue.getMessages()).hasSize(1);
    assertThat(fakeJobsQueue.getMessages().get(0).jobId()).isEqualTo("job1");
  }

  @Test
  void continuationShouldResumeAndComplete() {
    // arrange
    fakeClock.setTime(Instant.ofEpochSecond(1700000000));

    createImportWithRows("jordan", "import1", AppraiseJobProcessor.BATCH_SIZE + 5);

    var jobItem = new TcgInventoryItem();
    jobItem.setPk(TcgInventoryItem.formatUserPk("jordan"));
    jobItem.setSk(TcgInventoryItem.formatJobSk("job1"));
    jobItem.setJobId("job1");
    jobItem.setJobType("appraise");
    jobItem.setStatus("running");
    jobItem.setImportId("import1");
    jobItem.setContinuation(AppraiseJobProcessor.BATCH_SIZE);
    jobItem.setProcessedCount(AppraiseJobProcessor.BATCH_SIZE);
    jobItem.setCreatedAt(Instant.ofEpochSecond(1700000000));
    tcgInventoryTable.putItem(jobItem);

    // act
    jobsHandler.handleRequest(buildSqsEvent("jordan", "job1", "appraise"), null);

    // assert
    var updatedJob =
        tcgInventoryTable.getItem(
            Key.builder()
                .partitionValue(TcgInventoryItem.formatUserPk("jordan"))
                .sortValue(TcgInventoryItem.formatJobSk("job1"))
                .build());
    assertThat(updatedJob.getStatus()).isEqualTo("succeeded");
    assertThat(updatedJob.getProcessedCount()).isEqualTo(AppraiseJobProcessor.BATCH_SIZE + 5);

    assertThat(fakeJobsQueue.getMessages()).isEmpty();
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
    var updatedJob =
        tcgInventoryTable.getItem(
            Key.builder()
                .partitionValue(TcgInventoryItem.formatUserPk("jordan"))
                .sortValue(TcgInventoryItem.formatJobSk("job1"))
                .build());
    assertThat(updatedJob.getStatus()).isEqualTo("succeeded");
    assertThat(updatedJob.getUpdatedAt()).isEqualTo(Instant.ofEpochSecond(1700000100));
    assertThat(fakeJobsQueue.getMessages()).isEmpty();
  }

  @Test
  void duplicateDeliveryShouldNoOpWhenFailed() {
    // arrange
    fakeClock.setTime(Instant.ofEpochSecond(1700000000));

    var jobItem = new TcgInventoryItem();
    jobItem.setPk(TcgInventoryItem.formatUserPk("jordan"));
    jobItem.setSk(TcgInventoryItem.formatJobSk("job1"));
    jobItem.setJobId("job1");
    jobItem.setJobType("appraise");
    jobItem.setStatus("failed");
    jobItem.setImportId("import1");
    jobItem.setError("something went wrong");
    jobItem.setCreatedAt(Instant.ofEpochSecond(1700000000));
    jobItem.setUpdatedAt(Instant.ofEpochSecond(1700000100));
    tcgInventoryTable.putItem(jobItem);

    // act
    jobsHandler.handleRequest(buildSqsEvent("jordan", "job1", "appraise"), null);

    // assert
    var updatedJob =
        tcgInventoryTable.getItem(
            Key.builder()
                .partitionValue(TcgInventoryItem.formatUserPk("jordan"))
                .sortValue(TcgInventoryItem.formatJobSk("job1"))
                .build());
    assertThat(updatedJob.getStatus()).isEqualTo("failed");
    assertThat(updatedJob.getError()).isEqualTo("something went wrong");
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
    var updatedJob =
        tcgInventoryTable.getItem(
            Key.builder()
                .partitionValue(TcgInventoryItem.formatUserPk("jordan"))
                .sortValue(TcgInventoryItem.formatJobSk("job1"))
                .build());
    assertThat(updatedJob.getStatus()).isEqualTo("succeeded");
    assertThat(fakeJobsQueue.getMessages()).isEmpty();
  }

  private TcgInventoryItem createImportWithRows(String user, String importId, int rowCount) {
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
      rowItem.setCollectorNumber(String.valueOf(i));
      rowItem.setFinish("normal");
      rowItem.setCondition("NM");
      rowItem.setScryfallId("scryfall-" + i);
      rowItem.setLanguage("en");
      tcgInventoryTable.putItem(rowItem);
    }

    return importItem;
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
