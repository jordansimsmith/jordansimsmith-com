package com.jordansimsmith.tcginventory.pricing;

import static org.assertj.core.api.Assertions.assertThat;

import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jordansimsmith.dynamodb.DynamoDbContainer;
import com.jordansimsmith.dynamodb.DynamoDbUtils;
import com.jordansimsmith.tcginventory.JobItem;
import com.jordansimsmith.tcginventory.TcgInventoryTable;
import com.jordansimsmith.tcginventory.TcgInventoryTestFactory;
import com.jordansimsmith.tcginventory.settings.SettingsItem;
import java.net.URI;
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
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;

@Testcontainers
public class PricingHandlerIntegrationTest {
  @Container private static final DynamoDbContainer dynamoDbContainer = new DynamoDbContainer();

  private static final URI UNUSED_S3_ENDPOINT = URI.create("http://localhost:1");

  private TcgInventoryTestFactory factory;
  private ObjectMapper objectMapper;
  private PricingRepository pricingRepository;
  private GetPricingHandler getPricingHandler;
  private UpdatePricingHandler updatePricingHandler;

  @BeforeAll
  static void setUpBeforeClass() {
    var factory =
        TcgInventoryTestFactory.create(dynamoDbContainer.getEndpoint(), UNUSED_S3_ENDPOINT);
    DynamoDbUtils.createTable(factory.dynamoDbClient(), factory.tableDefinition());
  }

  @BeforeEach
  void setUp() {
    factory = TcgInventoryTestFactory.create(dynamoDbContainer.getEndpoint(), UNUSED_S3_ENDPOINT);
    DynamoDbUtils.reset(factory.dynamoDbClient());
    objectMapper = factory.objectMapper();
    factory.fakeSecrets().set("tcg_inventory", "{}");
    pricingRepository = new PricingRepository(factory);
    getPricingHandler = new GetPricingHandler(factory);
    updatePricingHandler = new UpdatePricingHandler(factory);
  }

  @Test
  void getPricingShouldReturnDefaultWhenNoRecordExists() throws Exception {
    // act
    var response = getPricingHandler.handleRequest(buildEvent("jordan", null), null);

    // assert
    assertPriceFloor(response, 200, "0.25");
    assertThat(pricingRepository.get("jordan")).isNull();
  }

  @Test
  void getPricingShouldReturnDefaultWhenStoredFloorIsAbsent() throws Exception {
    // arrange
    factory
        .dynamoDbClient()
        .putItem(
            PutItemRequest.builder()
                .tableName(TcgInventoryTable.TABLE_NAME)
                .item(
                    Map.of(
                        PricingItem.PK,
                        AttributeValue.fromS(PricingItem.formatPk("jordan")),
                        PricingItem.SK,
                        AttributeValue.fromS(PricingItem.formatSk())))
                .build());

    // act
    var response = getPricingHandler.handleRequest(buildEvent("jordan", null), null);

    // assert
    assertPriceFloor(response, 200, "0.25");
    var storedItem = pricingRepository.get("jordan");
    assertThat(storedItem).isNotNull();
    assertThat(storedItem.getPriceFloor()).isNull();
  }

  @Test
  void getPricingShouldFormatStoredFloorToTwoDecimalPlaces() throws Exception {
    // arrange
    factory
        .dynamoDbClient()
        .putItem(
            PutItemRequest.builder()
                .tableName(TcgInventoryTable.TABLE_NAME)
                .item(
                    Map.of(
                        PricingItem.PK,
                        AttributeValue.fromS(PricingItem.formatPk("jordan")),
                        PricingItem.SK,
                        AttributeValue.fromS(PricingItem.formatSk()),
                        PricingItem.USER,
                        AttributeValue.fromS("jordan"),
                        PricingItem.PRICE_FLOOR,
                        AttributeValue.fromS("1.2")))
                .build());

    // act
    var response = getPricingHandler.handleRequest(buildEvent("jordan", null), null);

    // assert
    assertPriceFloor(response, 200, "1.20");
  }

  @Test
  void updatePricingShouldNormalizeAndReturnSavedFloor() throws Exception {
    // act
    var response =
        updatePricingHandler.handleRequest(buildEvent("jordan", "{\"price_floor\":\"0.5\"}"), null);

    // assert
    assertPriceFloor(response, 200, "0.50");
    var storedItem = pricingRepository.get("jordan");
    assertThat(storedItem).isNotNull();
    assertThat(storedItem.getPriceFloor()).isEqualTo("0.50");
  }

  @Test
  void updatePricingShouldAllowZeroAndReturnNormalizedValue() throws Exception {
    // act
    var response =
        updatePricingHandler.handleRequest(buildEvent("jordan", "{\"price_floor\":\"0\"}"), null);

    // assert
    assertPriceFloor(response, 200, "0.00");
    var storedItem = pricingRepository.get("jordan");
    assertThat(storedItem).isNotNull();
    assertThat(storedItem.getPriceFloor()).isEqualTo("0.00");
  }

  @Test
  void updatePricingShouldDeserializeNumericJsonIntoBigDecimal() throws Exception {
    // act
    var response =
        updatePricingHandler.handleRequest(buildEvent("jordan", "{\"price_floor\":0.5}"), null);

    // assert
    assertPriceFloor(response, 200, "0.50");
    assertThat(pricingRepository.get("jordan").getPriceFloor()).isEqualTo("0.50");
  }

  @Test
  void updatePricingShouldLeaveFloorUnchangedWhenPatchOmitsIt() throws Exception {
    // act
    var unsetResponse = updatePricingHandler.handleRequest(buildEvent("jordan", "{}"), null);

    // assert
    assertPriceFloor(unsetResponse, 200, "0.25");
    assertThat(pricingRepository.get("jordan")).isNull();

    // arrange
    updatePricingHandler.handleRequest(buildEvent("jordan", "{\"price_floor\":\"0.50\"}"), null);

    // act
    var omittedResponse =
        updatePricingHandler.handleRequest(buildEvent("jordan", "{\"other\":\"ignored\"}"), null);
    var nullResponse =
        updatePricingHandler.handleRequest(buildEvent("jordan", "{\"price_floor\":null}"), null);

    // assert
    assertPriceFloor(omittedResponse, 200, "0.50");
    assertPriceFloor(nullResponse, 200, "0.50");
    assertThat(pricingRepository.get("jordan").getPriceFloor()).isEqualTo("0.50");
  }

  @Test
  void updatePricingShouldOverwriteAndReadExistingFloor() throws Exception {
    // arrange
    updatePricingHandler.handleRequest(buildEvent("jordan", "{\"price_floor\":\"0.50\"}"), null);

    // act
    var updateResponse =
        updatePricingHandler.handleRequest(
            buildEvent("jordan", "{\"price_floor\":\"1.27\"}"), null);
    var getResponse = getPricingHandler.handleRequest(buildEvent("jordan", null), null);

    // assert
    assertPriceFloor(updateResponse, 200, "1.27");
    assertPriceFloor(getResponse, 200, "1.27");
  }

  @Test
  void updatePricingShouldRejectInvalidBodiesWithoutChangingStoredFloor() throws Exception {
    // arrange
    updatePricingHandler.handleRequest(buildEvent("jordan", "{\"price_floor\":\"0.50\"}"), null);
    var invalidBodies =
        List.of(
            "not-json",
            "[]",
            "null",
            "\"value\"",
            "{\"price_floor\":true}",
            "{\"price_floor\":[]}",
            "{\"price_floor\":{}}",
            "{\"price_floor\":\"-0.01\"}",
            "{\"price_floor\":\"0.501\"}",
            "{\"price_floor\":0.501}");

    // act
    var missingBodyResponse = updatePricingHandler.handleRequest(buildEvent("jordan", null), null);

    // assert
    assertThat(missingBodyResponse.getStatusCode()).isEqualTo(400);
    assertThat(objectMapper.readTree(missingBodyResponse.getBody()).get("message").asText())
        .isNotBlank();
    for (var invalidBody : invalidBodies) {
      var response = updatePricingHandler.handleRequest(buildEvent("jordan", invalidBody), null);
      assertThat(response.getStatusCode()).isEqualTo(400);
      assertThat(objectMapper.readTree(response.getBody()).get("message").asText()).isNotBlank();
      var storedItem = pricingRepository.get("jordan");
      assertThat(storedItem).isNotNull();
      assertThat(storedItem.getPriceFloor()).isEqualTo("0.50");
    }
  }

  @Test
  void pricingShouldBeIsolatedByAuthenticatedUser() throws Exception {
    // arrange
    var updateResponse =
        updatePricingHandler.handleRequest(
            buildEvent("jordan", "{\"user\":\"alice\",\"price_floor\":\"0.75\"}"), null);

    // act
    var jordanResponse = getPricingHandler.handleRequest(buildEvent("jordan", null), null);
    var aliceResponse = getPricingHandler.handleRequest(buildEvent("alice", null), null);

    // assert
    assertPriceFloor(updateResponse, 200, "0.75");
    assertPriceFloor(jordanResponse, 200, "0.75");
    assertPriceFloor(aliceResponse, 200, "0.25");
    assertThat(pricingRepository.get("alice")).isNull();
  }

  @Test
  void updatePricingShouldConflictWhileJobIsQueuedOrRunning() throws Exception {
    // arrange
    updatePricingHandler.handleRequest(buildEvent("jordan", "{\"price_floor\":\"0.50\"}"), null);
    var jobItem = createJob("active-job", "queued");
    factory.jobTable().putItem(jobItem);

    // act
    var queuedResponse =
        updatePricingHandler.handleRequest(
            buildEvent("jordan", "{\"price_floor\":\"0.75\"}"), null);
    jobItem.setStatus("running");
    factory.jobTable().putItem(jobItem);
    var runningResponse =
        updatePricingHandler.handleRequest(
            buildEvent("jordan", "{\"price_floor\":\"0.75\"}"), null);
    var readResponse = getPricingHandler.handleRequest(buildEvent("jordan", null), null);

    // assert
    assertConflict(queuedResponse);
    assertConflict(runningResponse);
    assertPriceFloor(readResponse, 200, "0.50");
    var storedItem = pricingRepository.get("jordan");
    assertThat(storedItem).isNotNull();
    assertThat(storedItem.getPriceFloor()).isEqualTo("0.50");
  }

  @Test
  void updatePricingShouldAllowWritesWhenJobsAreTerminal() throws Exception {
    // arrange
    var succeededJob = createJob("succeeded-job", "succeeded");
    var failedJob = createJob("failed-job", "failed");
    factory.jobTable().putItem(succeededJob);
    factory.jobTable().putItem(failedJob);

    // act
    var response =
        updatePricingHandler.handleRequest(
            buildEvent("jordan", "{\"price_floor\":\"0.75\"}"), null);

    // assert
    assertPriceFloor(response, 200, "0.75");
  }

  @Test
  void updatePricingShouldOnlyChangePricingConfiguration() throws Exception {
    // arrange
    var existingJobs = jobsForUser("jordan");
    assertThat(existingJobs)
        .allSatisfy(job -> assertThat(job.getStatus()).isNotIn("queued", "running"));
    var jobIdsBefore = existingJobs.stream().map(JobItem::getJobId).toList();
    factory.fakeSecrets().set("tcg_inventory", "{\"jordan\":\"existing-token\"}");
    var settingsItem = SettingsItem.create("jordan", Instant.ofEpochSecond(1700000000));
    settingsItem.setTrackOrdersAfter(Instant.ofEpochSecond(1700001000));
    factory.settingsTable().putItem(settingsItem);
    var inventoryKey =
        Map.of(
            PricingItem.PK, AttributeValue.fromS("USER#jordan#SKU#sentinel"),
            PricingItem.SK, AttributeValue.fromS("SKU"));
    factory
        .dynamoDbClient()
        .putItem(
            PutItemRequest.builder()
                .tableName(TcgInventoryTable.TABLE_NAME)
                .item(
                    Map.of(
                        PricingItem.PK,
                        AttributeValue.fromS("USER#jordan#SKU#sentinel"),
                        PricingItem.SK,
                        AttributeValue.fromS("SKU"),
                        "dirty",
                        AttributeValue.fromBool(true),
                        "last_published_price",
                        AttributeValue.fromS("1.50")))
                .build());

    // act
    var updateResponse =
        updatePricingHandler.handleRequest(
            buildEvent("jordan", "{\"price_floor\":\"0.50\"}"), null);

    // assert
    assertPriceFloor(updateResponse, 200, "0.50");
    assertThat(factory.fakeSecrets().get("tcg_inventory"))
        .isEqualTo("{\"jordan\":\"existing-token\"}");
    var storedSettings =
        factory
            .settingsTable()
            .getItem(
                Key.builder()
                    .partitionValue(SettingsItem.formatPk("jordan"))
                    .sortValue(SettingsItem.formatSk())
                    .build());
    assertThat(storedSettings).isNotNull();
    assertThat(storedSettings.getUpdatedAt()).isEqualTo(Instant.ofEpochSecond(1700000000));
    assertThat(storedSettings.getTrackOrdersAfter()).isEqualTo(Instant.ofEpochSecond(1700001000));
    var inventoryItem =
        factory
            .dynamoDbClient()
            .getItem(
                GetItemRequest.builder()
                    .tableName(TcgInventoryTable.TABLE_NAME)
                    .key(inventoryKey)
                    .consistentRead(true)
                    .build())
            .item();
    assertThat(inventoryItem.get("dirty").bool()).isTrue();
    assertThat(inventoryItem.get("last_published_price").s()).isEqualTo("1.50");
    assertThat(factory.fakeJobsQueue().getMessages()).isEmpty();
    assertThat(jobsForUser("jordan").stream().map(JobItem::getJobId).toList())
        .containsExactlyInAnyOrderElementsOf(jobIdsBefore);
  }

  private void assertPriceFloor(
      APIGatewayV2HTTPResponse response, int statusCode, String priceFloor) throws Exception {
    assertThat(response.getStatusCode()).isEqualTo(statusCode);
    assertThat(objectMapper.readTree(response.getBody()).get("price_floor").asText())
        .isEqualTo(priceFloor);
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
