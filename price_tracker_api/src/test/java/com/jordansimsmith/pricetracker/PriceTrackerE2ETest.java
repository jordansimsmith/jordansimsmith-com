package com.jordansimsmith.pricetracker;

import static org.assertj.core.api.Assertions.assertThat;

import com.jordansimsmith.dynamodb.DynamoDbUtils;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.InvocationType;
import software.amazon.awssdk.services.lambda.model.InvokeRequest;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;

@Testcontainers
public class PriceTrackerE2ETest {

  @Container
  private static final PriceTrackerContainer priceTrackerContainer = new PriceTrackerContainer();

  @BeforeEach
  void setup() {
    var dynamoDbClient =
        DynamoDbClient.builder().endpointOverride(priceTrackerContainer.getLocalstackUrl()).build();

    DynamoDbUtils.reset(dynamoDbClient);
  }

  // skipped in CI because cloudflare blocks requests to the product websites from github actions
  // agents
  @Test
  @DisabledIfEnvironmentVariable(named = "CI", matches = "true")
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
        "https://www.chemistwarehouse.co.nz/buy/74329/inc-100-dynamic-whey-chocolate-flavour-2kg";
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
    assertThat(messageBody).contains("$1234.56 ->");
  }

  // skipped in CI because cloudflare blocks requests to the product websites from github actions
  // agents
  @Test
  @DisabledIfEnvironmentVariable(named = "CI", matches = "true")
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

    var productUrl = "https://www.nzprotein.co.nz/product/nz-whey-1kg-2-2lbs";
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
    assertThat(messageBody).contains("$4567.89 ->");
  }

  // skipped in CI because cloudflare blocks requests to the product websites from github actions
  // agents
  @Test
  @DisabledIfEnvironmentVariable(named = "CI", matches = "true")
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

    var productUrl = "https://nzmuscle.co.nz/products/shotgun-whey-protein?variant=51471561195701";
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
    assertThat(messageBody).contains("$8901.23 ->");
  }
}
