package com.jordansimsmith.auctiontracker;

import static org.assertj.core.api.Assertions.assertThat;

import com.jordansimsmith.dynamodb.DynamoDbUtils;
import java.util.Base64;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.InvocationType;
import software.amazon.awssdk.services.lambda.model.InvokeRequest;
import software.amazon.awssdk.services.lambda.model.LogType;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;

@Testcontainers
public class AuctionTrackerE2ETest {
  private static final Logger LOGGER = LoggerFactory.getLogger(AuctionTrackerE2ETest.class);

  @Container
  private static final AuctionTrackerContainer auctionTrackerContainer =
      new AuctionTrackerContainer();

  @BeforeEach
  void setup() {
    var dynamoDbClient =
        DynamoDbClient.builder()
            .endpointOverride(auctionTrackerContainer.getLocalstackUrl())
            .build();

    DynamoDbUtils.reset(dynamoDbClient);
  }

  @Test
  @DisabledIfEnvironmentVariable(named = "CI", matches = "true")
  void shouldUpdateItemsAndSendDigestWithNotification() throws Exception {
    // arrange
    var lambdaClient =
        LambdaClient.builder().endpointOverride(auctionTrackerContainer.getLocalstackUrl()).build();
    var sqsClient =
        SqsClient.builder().endpointOverride(auctionTrackerContainer.getLocalstackUrl()).build();

    // act - invoke update items handler
    var updateRequest =
        InvokeRequest.builder()
            .functionName("update_items_handler")
            .invocationType(InvocationType.REQUEST_RESPONSE)
            .logType(LogType.TAIL)
            .build();
    var updateResponse = lambdaClient.invoke(updateRequest);
    LOGGER.info(new String(Base64.getDecoder().decode(updateResponse.logResult())));
    assertThat(updateResponse.statusCode()).isEqualTo(200);
    assertThat(updateResponse.functionError())
        .withFailMessage(new String(updateResponse.payload().asByteArray()))
        .isNull();

    // act - invoke send digest handler
    var digestRequest =
        InvokeRequest.builder()
            .functionName("send_digest_handler")
            .invocationType(InvocationType.REQUEST_RESPONSE)
            .logType(LogType.TAIL)
            .build();
    var digestResponse = lambdaClient.invoke(digestRequest);
    LOGGER.info(new String(Base64.getDecoder().decode(digestResponse.logResult())));
    assertThat(digestResponse.statusCode()).isEqualTo(200);
    assertThat(digestResponse.functionError())
        .withFailMessage(new String(digestResponse.payload().asByteArray()))
        .isNull();

    // assert - verify digest message was sent to SNS/SQS
    var queueName = "auction-tracker-test-queue";
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
    assertThat(messageBody).contains("Auction Tracker Daily Digest");
  }
}
