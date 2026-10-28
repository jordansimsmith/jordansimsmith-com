package com.jordansimsmith.tcginventory.pricing;

import static org.assertj.core.api.Assertions.assertThat;

import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jordansimsmith.dynamodb.DynamoDbContainer;
import com.jordansimsmith.dynamodb.DynamoDbUtils;
import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.tcginventory.JobItem;
import com.jordansimsmith.tcginventory.TcgInventoryTable;
import com.jordansimsmith.tcginventory.TcgInventoryTestFactory;
import com.jordansimsmith.tcginventory.pricing.client.UpdatePricingRequest;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;

@Testcontainers
public class PricingHandlerIntegrationTest {
  @Container private static final DynamoDbContainer dynamoDbContainer = new DynamoDbContainer();

  private TcgInventoryTestFactory factory;
  private ObjectMapper objectMapper;
  private CreateRepriceHandler createRepriceHandler;
  private GetRepriceHandler getRepriceHandler;

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
    factory.fakeClock().setTime(Instant.ofEpochSecond(1700000000));
    factory.fakeUlidGenerator().reset();
    factory.fakeJobsQueue().reset();
    objectMapper = factory.objectMapper();
    factory.fakeSecrets().set("tcg_inventory", "{}");
    createRepriceHandler = new CreateRepriceHandler(factory);
    getRepriceHandler = new GetRepriceHandler(factory);
  }

  @Test
  void createRepriceShouldQueueJobWithoutPricingMetadata() throws Exception {
    // act
    var response = createRepriceHandler.handleRequest(buildEvent("jordan", null), null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(202);
    assertThat(factory.fakeJobsQueue().getSends()).hasSize(1);
    var send = factory.fakeJobsQueue().getSends().get(0);
    assertThat(send.message().user()).isEqualTo("jordan");
    assertThat(send.message().jobType()).isEqualTo("reprice");
    assertThat(send.messageGroupId()).isEqualTo("jordan");
    assertThat(send.messageDeduplicationId()).isEqualTo(send.message().jobId() + "#0");

    var job = getJob("jordan", send.message().jobId());
    assertThat(job.getStatus()).isEqualTo("queued");
    assertThat(job.getProcessedCount()).isZero();
    assertThat(rawJob("jordan", send.message().jobId()))
        .doesNotContainKeys("price_floor", "repriced_sku_count");
  }

  @Test
  void createRepriceShouldEnqueueIndependentlyForEachUser() throws Exception {
    // arrange
    factory
        .pricingService()
        .updatePricing(new RequestContext("jordan"), new UpdatePricingRequest(new BigDecimal("0")));
    factory
        .pricingService()
        .updatePricing(
            new RequestContext("alice"), new UpdatePricingRequest(new BigDecimal("0.75")));

    // act
    var jordanResponse = createRepriceHandler.handleRequest(buildEvent("jordan", null), null);
    var aliceResponse = createRepriceHandler.handleRequest(buildEvent("alice", null), null);

    // assert
    assertThat(jordanResponse.getStatusCode()).isEqualTo(202);
    assertThat(aliceResponse.getStatusCode()).isEqualTo(202);
    assertThat(
            factory.fakeJobsQueue().getSends().stream().map(send -> send.messageGroupId()).toList())
        .containsExactly("jordan", "alice");
    assertThat(rawJob("jordan", factory.fakeJobsQueue().getSends().get(0).message().jobId()))
        .doesNotContainKeys("price_floor", "repriced_sku_count");
    assertThat(rawJob("alice", factory.fakeJobsQueue().getSends().get(1).message().jobId()))
        .doesNotContainKeys("price_floor", "repriced_sku_count");
  }

  @Test
  void createRepriceShouldReturnAcceptedWithoutEnqueueWhenRepriceAlreadyActive() {
    // arrange
    factory.jobTable().putItem(createRepriceJob("existing-reprice", "jordan", "running"));

    // act
    var response = createRepriceHandler.handleRequest(buildEvent("jordan", null), null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(202);
    assertThat(factory.fakeJobsQueue().getMessages()).isEmpty();
    assertThat(jobsForUser("jordan")).hasSize(1);
  }

  @Test
  void createRepriceShouldConflictWithDifferentActiveJob() throws Exception {
    // arrange
    factory.jobTable().putItem(createJob("active-publish", "queued"));

    // act
    var response = createRepriceHandler.handleRequest(buildEvent("jordan", null), null);

    // assert
    assertConflict(response);
    assertThat(factory.fakeJobsQueue().getMessages()).isEmpty();
    assertThat(jobsForUser("jordan")).hasSize(1);
  }

  @Test
  void getRepriceShouldReturnLatestRunWithPartialCountersAndTerminalFields() throws Exception {
    // arrange
    var oldJob = createRepriceJob("01-older", "jordan", "succeeded");
    oldJob.setUpdatedAt(Instant.ofEpochSecond(1700000100));
    factory.jobTable().putItem(oldJob);
    var latestJob = createRepriceJob("02-latest", "jordan", "failed");
    latestJob.setProcessedCount(100);
    latestJob.setError("SKU abc: missing NZ market price");
    latestJob.setUpdatedAt(Instant.ofEpochSecond(1700000200));
    factory.jobTable().putItem(latestJob);

    // act
    var response = getRepriceHandler.handleRequest(buildEvent("jordan", null), null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(200);
    var body = objectMapper.readTree(response.getBody());
    assertThat(body.get("job_id").asText()).isEqualTo("02-latest");
    assertThat(body.get("status").asText()).isEqualTo("failed");
    assertThat(body.get("processed_sku_count").asInt()).isEqualTo(100);
    assertThat(body.get("error").asText()).isEqualTo("SKU abc: missing NZ market price");
    assertThat(body.get("started_at").asLong()).isEqualTo(1700000000);
    assertThat(body.get("finished_at").asLong()).isEqualTo(1700000200);
    assertThat(body.has("continuation")).isFalse();
    assertThat(body.has("total_sku_count")).isFalse();
  }

  @Test
  void getRepriceShouldReturnNullFinishedAtWhileJobIsActive() throws Exception {
    // arrange
    var job = createRepriceJob("active-reprice", "jordan", "running");
    job.setProcessedCount(100);
    job.setUpdatedAt(Instant.ofEpochSecond(1700000200));
    factory.jobTable().putItem(job);

    // act
    var response = getRepriceHandler.handleRequest(buildEvent("jordan", null), null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(200);
    var body = objectMapper.readTree(response.getBody());
    assertThat(body.get("status").asText()).isEqualTo("running");
    assertThat(body.get("processed_sku_count").asInt()).isEqualTo(100);
    assertThat(body.get("finished_at").isNull()).isTrue();
    assertThat(body.has("price_floor")).isFalse();
    assertThat(body.has("repriced_sku_count")).isFalse();
  }

  @Test
  void getRepriceShouldReturnNotFoundWhenNoRunExists() {
    // act
    var response = getRepriceHandler.handleRequest(buildEvent("jordan", null), null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(404);
  }

  private void assertConflict(APIGatewayV2HTTPResponse response) throws Exception {
    assertThat(response.getStatusCode()).isEqualTo(409);
    assertThat(objectMapper.readTree(response.getBody()).get("message").asText())
        .isEqualTo("another job is in progress");
  }

  private JobItem createJob(String jobId, String status) {
    var jobItem =
        JobItem.create("jordan", jobId, "publish", null, null, Instant.ofEpochSecond(1700000000));
    jobItem.setStatus(status);
    return jobItem;
  }

  private JobItem createRepriceJob(String jobId, String user, String status) {
    var jobItem =
        JobItem.create(user, jobId, "reprice", null, null, Instant.ofEpochSecond(1700000000));
    jobItem.setStatus(status);
    jobItem.setProcessedCount(0);
    return jobItem;
  }

  private JobItem getJob(String user, String jobId) {
    return factory
        .jobTable()
        .getItem(
            Key.builder()
                .partitionValue(JobItem.formatPk(user))
                .sortValue(JobItem.formatSk(jobId))
                .build());
  }

  private Map<String, AttributeValue> rawJob(String user, String jobId) {
    return factory
        .dynamoDbClient()
        .getItem(
            GetItemRequest.builder()
                .tableName(TcgInventoryTable.TABLE_NAME)
                .key(
                    Map.of(
                        JobItem.PK, AttributeValue.fromS(JobItem.formatPk(user)),
                        JobItem.SK, AttributeValue.fromS(JobItem.formatSk(jobId))))
                .consistentRead(true)
                .build())
        .item();
  }

  private List<JobItem> jobsForUser(String user) {
    return factory
        .jobTable()
        .query(
            request ->
                request.queryConditional(
                    QueryConditional.sortBeginsWith(
                        Key.builder()
                            .partitionValue(JobItem.formatPk(user))
                            .sortValue(JobItem.JOB_PREFIX)
                            .build())))
        .items()
        .stream()
        .toList();
  }

  private APIGatewayV2HTTPEvent buildEvent(String user, String body) {
    var authHeader =
        "Basic "
            + Base64.getEncoder()
                .encodeToString((user + ":password").getBytes(StandardCharsets.UTF_8));
    return APIGatewayV2HTTPEvent.builder()
        .withHeaders(Map.of("Authorization", authHeader))
        .withBody(body)
        .build();
  }
}
