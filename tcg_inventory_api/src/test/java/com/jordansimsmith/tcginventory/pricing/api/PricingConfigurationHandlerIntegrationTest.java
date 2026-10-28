package com.jordansimsmith.tcginventory.pricing.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jordansimsmith.dynamodb.DynamoDbContainer;
import com.jordansimsmith.dynamodb.DynamoDbUtils;
import com.jordansimsmith.tcginventory.JobItem;
import com.jordansimsmith.tcginventory.TcgInventoryFactory;
import com.jordansimsmith.tcginventory.TcgInventoryTable;
import com.jordansimsmith.tcginventory.TcgInventoryTestFactory;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;

@Testcontainers
public class PricingConfigurationHandlerIntegrationTest {
  @Container private static final DynamoDbContainer dynamoDbContainer = new DynamoDbContainer();

  private TcgInventoryFactory factory;
  private ObjectMapper objectMapper;
  private GetPricingHandler getPricingHandler;
  private UpdatePricingHandler updatePricingHandler;

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
    objectMapper = factory.objectMapper();
    getPricingHandler = new GetPricingHandler(factory);
    updatePricingHandler = new UpdatePricingHandler(factory);
  }

  @Test
  void pricingHandlersShouldReadDefaultSaveNormalizeAndReadBackFloor() throws Exception {
    // act
    var defaultResponse = getPricingHandler.handleRequest(buildEvent("jordan", null), null);
    var updateResponse =
        updatePricingHandler.handleRequest(buildEvent("jordan", "{\"price_floor\":\"1.2\"}"), null);
    var getResponse = getPricingHandler.handleRequest(buildEvent("jordan", null), null);

    // assert
    assertPriceFloor(defaultResponse.getBody(), "0.25");
    assertPriceFloor(updateResponse.getBody(), "1.20");
    assertPriceFloor(getResponse.getBody(), "1.20");
    assertThat(factory.dynamoDbClient().getItem(getPricingItemRequest("jordan")).item())
        .containsEntry("user", AttributeValue.fromS("jordan"))
        .containsEntry("price_floor", AttributeValue.fromS("1.20"));
  }

  @Test
  void updatePricingShouldIgnoreOmittedAndNullFloorsButRejectUnknownFields() throws Exception {
    // arrange
    updatePricingHandler.handleRequest(buildEvent("jordan", "{\"price_floor\":\"0.50\"}"), null);

    // act
    var omitted = updatePricingHandler.handleRequest(buildEvent("jordan", "{}"), null);
    var explicitNull =
        updatePricingHandler.handleRequest(buildEvent("jordan", "{\"price_floor\":null}"), null);
    var unknown =
        updatePricingHandler.handleRequest(buildEvent("jordan", "{\"unexpected\":true}"), null);

    // assert
    assertPriceFloor(omitted.getBody(), "0.50");
    assertPriceFloor(explicitNull.getBody(), "0.50");
    assertThat(unknown.getStatusCode()).isEqualTo(400);
    assertThat(objectMapper.readTree(unknown.getBody()).get("message").asText())
        .isEqualTo("Invalid request body");
    assertThat(factory.dynamoDbClient().getItem(getPricingItemRequest("jordan")).item())
        .containsEntry("price_floor", AttributeValue.fromS("0.50"));
  }

  @Test
  void updatePricingShouldRejectNegativeAndOverPrecisionValuesWithoutWriting() throws Exception {
    // arrange
    updatePricingHandler.handleRequest(buildEvent("jordan", "{\"price_floor\":\"0.50\"}"), null);

    // act
    var negative =
        updatePricingHandler.handleRequest(
            buildEvent("jordan", "{\"price_floor\":\"-0.01\"}"), null);
    var overPrecision =
        updatePricingHandler.handleRequest(
            buildEvent("jordan", "{\"price_floor\":\"0.001\"}"), null);

    // assert
    assertThat(negative.getStatusCode()).isEqualTo(400);
    assertThat(overPrecision.getStatusCode()).isEqualTo(400);
    assertThat(objectMapper.readTree(negative.getBody()).get("message").asText())
        .isEqualTo("price_floor must be a nonnegative decimal with at most two places");
    assertThat(objectMapper.readTree(overPrecision.getBody()).get("message").asText())
        .isEqualTo("price_floor must be a nonnegative decimal with at most two places");
    assertThat(factory.dynamoDbClient().getItem(getPricingItemRequest("jordan")).item())
        .containsEntry("price_floor", AttributeValue.fromS("0.50"));
  }

  @Test
  void updatePricingShouldAllowWritesWhileJobIsActivePendingJobsIntegration() throws Exception {
    // arrange
    var job =
        JobItem.create("jordan", "job1", "publish", null, null, Instant.ofEpochSecond(1700000000));
    job.setStatus("running");
    factory.jobTable().putItem(job);

    // act
    var response =
        updatePricingHandler.handleRequest(buildEvent("jordan", "{\"price_floor\":0}"), null);

    // assert
    assertPriceFloor(response.getBody(), "0.00");
  }

  @Test
  void pricingShouldBeIsolatedByAuthenticatedUserAndPreserveUnmappedAttributes() throws Exception {
    // arrange
    factory
        .dynamoDbClient()
        .putItem(
            PutItemRequest.builder()
                .tableName(TcgInventoryTable.TABLE_NAME)
                .item(
                    Map.of(
                        "pk", AttributeValue.fromS("USER#jordan"),
                        "sk", AttributeValue.fromS("PRICING"),
                        "user", AttributeValue.fromS("jordan"),
                        "other_attribute", AttributeValue.fromS("preserved")))
                .build());

    // act
    var jordanResponse =
        updatePricingHandler.handleRequest(
            buildEvent("jordan", "{\"price_floor\":\"0.75\"}"), null);
    var aliceResponse = getPricingHandler.handleRequest(buildEvent("alice", null), null);

    // assert
    assertPriceFloor(jordanResponse.getBody(), "0.75");
    assertPriceFloor(aliceResponse.getBody(), "0.25");
    assertThat(factory.dynamoDbClient().getItem(getPricingItemRequest("jordan")).item())
        .containsEntry("other_attribute", AttributeValue.fromS("preserved"));
    assertThat(factory.dynamoDbClient().getItem(getPricingItemRequest("alice")).item()).isEmpty();
  }

  private void assertPriceFloor(String body, String priceFloor) throws Exception {
    assertThat(objectMapper.readTree(body).get("price_floor").asText()).isEqualTo(priceFloor);
  }

  private GetItemRequest getPricingItemRequest(String user) {
    return GetItemRequest.builder()
        .tableName(TcgInventoryTable.TABLE_NAME)
        .key(
            Map.of(
                "pk", AttributeValue.fromS("USER#" + user),
                "sk", AttributeValue.fromS("PRICING")))
        .consistentRead(true)
        .build();
  }

  private APIGatewayV2HTTPEvent buildEvent(String user, String body) {
    var auth = Base64.getEncoder().encodeToString((user + ":password").getBytes());
    return APIGatewayV2HTTPEvent.builder()
        .withHeaders(Map.of("Authorization", "Basic " + auth))
        .withBody(body)
        .build();
  }
}
