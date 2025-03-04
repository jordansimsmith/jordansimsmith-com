package com.jordansimsmith.pricetracker;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;
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
  private final PriceTrackerContainer priceTrackerContainer = new PriceTrackerContainer();

  @Test
  void shouldStartContainer() {
    assertThat(priceTrackerContainer.isRunning()).isTrue();
  }

  @Test
  void shouldTrackPricesAndSendNotifications() throws Exception {
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
    assertThat(lambdaResponse.functionError()).isNull();

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
}
