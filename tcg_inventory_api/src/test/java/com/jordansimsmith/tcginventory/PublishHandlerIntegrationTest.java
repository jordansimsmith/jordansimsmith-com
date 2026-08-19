package com.jordansimsmith.tcginventory;

import static org.assertj.core.api.Assertions.assertThat;

import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jordansimsmith.dynamodb.DynamoDbContainer;
import com.jordansimsmith.dynamodb.DynamoDbUtils;
import com.jordansimsmith.queue.FakeQueueClient;
import com.jordansimsmith.time.FakeClock;
import com.jordansimsmith.ulid.FakeUlidGenerator;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;

@Testcontainers
public class PublishHandlerIntegrationTest {

  private FakeClock fakeClock;
  private FakeUlidGenerator fakeUlidGenerator;
  private FakeQueueClient<JobMessage> fakeJobsQueue;
  private ObjectMapper objectMapper;
  private DynamoDbTable<TcgInventoryItem> tcgInventoryTable;

  private CreatePublishHandler createPublishHandler;
  private GetPublishHandler getPublishHandler;

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

    createPublishHandler = new CreatePublishHandler(factory);
    getPublishHandler = new GetPublishHandler(factory);
  }

  @Test
  void createPublishShouldCreateJobAndEnqueue() throws Exception {
    // arrange
    fakeClock.setTime(Instant.ofEpochSecond(1700000000));

    // act
    var response = createPublishHandler.handleRequest(buildEvent("jordan"), null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(202);

    assertThat(fakeJobsQueue.getSends()).hasSize(1);
    var send = fakeJobsQueue.getSends().get(0);
    assertThat(send.message().jobType()).isEqualTo("publish");
    assertThat(send.messageGroupId()).isEqualTo("jordan");

    var jobId = send.message().jobId();
    assertThat(send.messageDeduplicationId()).isEqualTo(jobId + "#0");
    var jobItem =
        tcgInventoryTable.getItem(
            Key.builder()
                .partitionValue(TcgInventoryItem.formatUserPk("jordan"))
                .sortValue(TcgInventoryItem.formatJobSk(jobId))
                .build());
    assertThat(jobItem).isNotNull();
    assertThat(jobItem.getJobType()).isEqualTo("publish");
    assertThat(jobItem.getStatus()).isEqualTo("queued");
  }

  @Test
  void createPublishShouldReturnExistingWhenActive() throws Exception {
    // arrange
    fakeClock.setTime(Instant.ofEpochSecond(1700000000));

    var jobItem =
        TcgInventoryItem.createJob(
            "jordan", "existing-job", "publish", null, Instant.ofEpochSecond(1700000000));
    jobItem.setStatus("running");
    jobItem.setProcessedCount(5);
    jobItem.setUpdatedAt(Instant.ofEpochSecond(1700000100));
    tcgInventoryTable.putItem(jobItem);

    // act
    var response = createPublishHandler.handleRequest(buildEvent("jordan"), null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(202);

    assertThat(fakeJobsQueue.getMessages()).isEmpty();
  }

  @Test
  void createPublishShouldCreateNewAfterPreviousCompleted() throws Exception {
    // arrange
    fakeClock.setTime(Instant.ofEpochSecond(1700000000));

    var completedJob =
        TcgInventoryItem.createJob(
            "jordan", "old-job", "publish", null, Instant.ofEpochSecond(1699999000));
    completedJob.setStatus("succeeded");
    completedJob.setProcessedCount(10);
    tcgInventoryTable.putItem(completedJob);

    // act
    var response = createPublishHandler.handleRequest(buildEvent("jordan"), null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(202);

    assertThat(fakeJobsQueue.getMessages()).hasSize(1);
    assertThat(fakeJobsQueue.getMessages().get(0).jobId()).isNotEqualTo("old-job");
  }

  @Test
  void getPublishShouldReturnLatestRunWithDirtyCount() throws Exception {
    // arrange
    fakeClock.setTime(Instant.ofEpochSecond(1700000000));

    var jobItem =
        TcgInventoryItem.createJob(
            "jordan", "pub-job", "publish", null, Instant.ofEpochSecond(1700000000));
    jobItem.setStatus("succeeded");
    jobItem.setProcessedCount(3);
    jobItem.setUpdatedAt(Instant.ofEpochSecond(1700000200));
    tcgInventoryTable.putItem(jobItem);

    var dirtySku =
        TcgInventoryItem.createSku(
            "jordan",
            "sku1#normal#NM",
            "sku1",
            "normal",
            "NM",
            "Test Card",
            "dom",
            "Dominaria",
            "1",
            null,
            null);
    tcgInventoryTable.putItem(dirtySku);

    // act
    var response = getPublishHandler.handleRequest(buildEvent("jordan"), null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(200);
    var body = objectMapper.readTree(response.getBody());
    assertThat(body.get("status").asText()).isEqualTo("succeeded");
    assertThat(body.get("published_sku_count").asInt()).isEqualTo(3);
    assertThat(body.get("total_sku_count").asInt()).isEqualTo(4);
    assertThat(body.get("error").isNull()).isTrue();
    assertThat(body.get("pending_sku_count").asInt()).isEqualTo(1);
    assertThat(body.get("started_at").asLong()).isEqualTo(1700000000);
    assertThat(body.get("finished_at").asLong()).isEqualTo(1700000200);
  }

  @Test
  void getPublishShouldReturn404WhenNeverRun() {
    // act
    var response = getPublishHandler.handleRequest(buildEvent("jordan"), null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(404);
  }

  private APIGatewayV2HTTPEvent buildEvent(String user) {
    var authHeader =
        "Basic "
            + Base64.getEncoder()
                .encodeToString((user + ":password").getBytes(StandardCharsets.UTF_8));
    return APIGatewayV2HTTPEvent.builder().withHeaders(Map.of("Authorization", authHeader)).build();
  }
}
