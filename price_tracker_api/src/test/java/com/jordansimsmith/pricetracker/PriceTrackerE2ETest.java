package com.jordansimsmith.pricetracker;

import static org.assertj.core.api.Assertions.assertThat;

import com.jordansimsmith.dynamodb.DynamoDbUtils;
import java.time.Instant;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.Network;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.InvocationType;
import software.amazon.awssdk.services.lambda.model.InvokeRequest;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;

public class PriceTrackerE2ETest {
  private static final String NETWORK_NAME = "price-tracker-e2e";
  private static final String CHEMIST_WAREHOUSE_STUB_ALIAS = "chemist-warehouse-stub";
  private static final String NZ_PROTEIN_STUB_ALIAS = "nz-protein-stub";
  private static final String NZ_MUSCLE_STUB_ALIAS = "nz-muscle-stub";

  private static final Network NETWORK =
      Network.builder().createNetworkCmdModifier(cmd -> cmd.withName(NETWORK_NAME)).build();

  private static final PriceTrackerWebsiteStubContainer priceTrackerWebsiteStubContainer =
      new PriceTrackerWebsiteStubContainer()
          .withNetwork(NETWORK)
          .withNetworkAliases(
              CHEMIST_WAREHOUSE_STUB_ALIAS, NZ_PROTEIN_STUB_ALIAS, NZ_MUSCLE_STUB_ALIAS);

  private static final PriceTrackerContainer priceTrackerContainer =
      new PriceTrackerContainer()
          .withNetwork(NETWORK)
          .withEnv("LAMBDA_DOCKER_NETWORK", NETWORK_NAME)
          .withEnv(
              "PRICE_TRACKER_CHEMIST_WAREHOUSE_BASE_URL",
              "http://" + CHEMIST_WAREHOUSE_STUB_ALIAS + ":8080")
          .withEnv("PRICE_TRACKER_NZ_PROTEIN_BASE_URL", "http://" + NZ_PROTEIN_STUB_ALIAS + ":8080")
          .withEnv("PRICE_TRACKER_NZ_MUSCLE_BASE_URL", "http://" + NZ_MUSCLE_STUB_ALIAS + ":8080");

  @BeforeAll
  static void setUpBeforeClass() {
    priceTrackerWebsiteStubContainer.start();
    priceTrackerContainer.start();
  }

  @AfterAll
  static void tearDownAfterClass() {
    priceTrackerContainer.stop();
    priceTrackerWebsiteStubContainer.stop();
    NETWORK.close();
  }

  @BeforeEach
  void setup() {
    var dynamoDbClient =
        DynamoDbClient.builder().endpointOverride(priceTrackerContainer.getLocalstackUrl()).build();

    DynamoDbUtils.reset(dynamoDbClient);
  }

  @Test
  void shouldTrackChemistWarehousePricesAndSendNotifications() throws Exception {
    // arrange
    var dynamoDbClient =
        DynamoDbClient.builder().endpointOverride(priceTrackerContainer.getLocalstackUrl()).build();
    var enhancedClient = DynamoDbEnhancedClient.builder().dynamoDbClient(dynamoDbClient).build();
    var priceTrackerTable =
        enhancedClient.table("price_tracker", TableSchema.fromBean(PriceTrackerItem.class));
    var lambdaClient =
        LambdaClient.builder().endpointOverride(priceTrackerContainer.getLocalstackUrl()).build();
    var sqsClient =
        SqsClient.builder().endpointOverride(priceTrackerContainer.getLocalstackUrl()).build();

    var productUrl =
        "http://chemist-warehouse-stub:8080/buy/74329/inc-100-dynamic-whey-chocolate-flavour-2kg";
    var productName = "Chemist Warehouse - Dynamic Whey 2kg - Chocolate";
    var productHistory =
        PriceTrackerItem.create(productUrl, productName, Instant.ofEpochSecond(1_000_000), 1234.56);
    priceTrackerTable.putItem(productHistory);

    // act
    var request =
        InvokeRequest.builder()
            .functionName("update_prices_handler")
            .invocationType(InvocationType.REQUEST_RESPONSE)
            .build();
    var lambdaResponse = lambdaClient.invoke(request);
    assertThat(lambdaResponse.statusCode()).isEqualTo(200);
    assertThat(lambdaResponse.functionError())
        .withFailMessage(new String(lambdaResponse.payload().asByteArray()))
        .isNull();

    // assert
    var queueName = "price-tracker-test-queue";
    var queueUrl = sqsClient.getQueueUrl(b -> b.queueName(queueName).build()).queueUrl();
    var receiveRequest =
        ReceiveMessageRequest.builder()
            .queueUrl(queueUrl)
            .maxNumberOfMessages(1)
            .waitTimeSeconds(10)
            .build();
    var messages = sqsClient.receiveMessage(receiveRequest).messages();
    assertThat(messages).isNotEmpty();

    var messageBody = messages.get(0).body();
    assertThat(messageBody).contains("price updated");
    assertThat(messageBody).contains(productName);
    assertThat(messageBody).contains(productUrl);
    assertThat(messageBody).contains("$1234.56 -> $52.00");
  }

  @Test
  void shouldTrackNzProteinPricesAndSendNotifications() throws Exception {
    // arrange
    var dynamoDbClient =
        DynamoDbClient.builder().endpointOverride(priceTrackerContainer.getLocalstackUrl()).build();
    var enhancedClient = DynamoDbEnhancedClient.builder().dynamoDbClient(dynamoDbClient).build();
    var priceTrackerTable =
        enhancedClient.table("price_tracker", TableSchema.fromBean(PriceTrackerItem.class));
    var lambdaClient =
        LambdaClient.builder().endpointOverride(priceTrackerContainer.getLocalstackUrl()).build();
    var sqsClient =
        SqsClient.builder().endpointOverride(priceTrackerContainer.getLocalstackUrl()).build();

    var productUrl = "http://nz-protein-stub:8080/product/nz-whey-1kg-2-2lbs";
    var productName = "NZ Protein - NZ Whey 1kg (2.2lbs)";
    var productHistory =
        PriceTrackerItem.create(productUrl, productName, Instant.ofEpochSecond(1_000_000), 4567.89);
    priceTrackerTable.putItem(productHistory);

    // act
    var request =
        InvokeRequest.builder()
            .functionName("update_prices_handler")
            .invocationType(InvocationType.REQUEST_RESPONSE)
            .build();
    var lambdaResponse = lambdaClient.invoke(request);
    assertThat(lambdaResponse.statusCode()).isEqualTo(200);
    assertThat(lambdaResponse.functionError())
        .withFailMessage(new String(lambdaResponse.payload().asByteArray()))
        .isNull();

    // assert
    var queueName = "price-tracker-test-queue";
    var queueUrl = sqsClient.getQueueUrl(b -> b.queueName(queueName).build()).queueUrl();
    var receiveRequest =
        ReceiveMessageRequest.builder()
            .queueUrl(queueUrl)
            .maxNumberOfMessages(1)
            .waitTimeSeconds(10)
            .build();
    var messages = sqsClient.receiveMessage(receiveRequest).messages();
    assertThat(messages).isNotEmpty();

    var messageBody = messages.get(0).body();
    assertThat(messageBody).contains("price updated");
    assertThat(messageBody).contains(productName);
    assertThat(messageBody).contains(productUrl);
    assertThat(messageBody).contains("$4567.89 -> $84.95");
  }

  @Test
  void shouldTrackNzMusclePricesAndSendNotifications() throws Exception {
    // arrange
    var dynamoDbClient =
        DynamoDbClient.builder().endpointOverride(priceTrackerContainer.getLocalstackUrl()).build();
    var enhancedClient = DynamoDbEnhancedClient.builder().dynamoDbClient(dynamoDbClient).build();
    var priceTrackerTable =
        enhancedClient.table("price_tracker", TableSchema.fromBean(PriceTrackerItem.class));
    var lambdaClient =
        LambdaClient.builder().endpointOverride(priceTrackerContainer.getLocalstackUrl()).build();
    var sqsClient =
        SqsClient.builder().endpointOverride(priceTrackerContainer.getLocalstackUrl()).build();

    var productUrl =
        "http://nz-muscle-stub:8080/products/shotgun-whey-protein?variant=51471561195701";
    var productName = "Shotgun Whey Protein Chocolate 2kg";
    var productHistory =
        PriceTrackerItem.create(productUrl, productName, Instant.ofEpochSecond(1_000_000), 8901.23);
    priceTrackerTable.putItem(productHistory);

    // act
    var request =
        InvokeRequest.builder()
            .functionName("update_prices_handler")
            .invocationType(InvocationType.REQUEST_RESPONSE)
            .build();
    var lambdaResponse = lambdaClient.invoke(request);
    assertThat(lambdaResponse.statusCode()).isEqualTo(200);
    assertThat(lambdaResponse.functionError())
        .withFailMessage(new String(lambdaResponse.payload().asByteArray()))
        .isNull();

    // assert
    var queueName = "price-tracker-test-queue";
    var queueUrl = sqsClient.getQueueUrl(b -> b.queueName(queueName).build()).queueUrl();
    var receiveRequest =
        ReceiveMessageRequest.builder()
            .queueUrl(queueUrl)
            .maxNumberOfMessages(1)
            .waitTimeSeconds(10)
            .build();
    var messages = sqsClient.receiveMessage(receiveRequest).messages();
    assertThat(messages).isNotEmpty();

    var messageBody = messages.get(0).body();
    assertThat(messageBody).contains("price updated");
    assertThat(messageBody).contains(productName);
    assertThat(messageBody).contains(productUrl);
    assertThat(messageBody).contains("$8901.23 -> $92.50");
  }
}
