package com.jordansimsmith.auctiontracker;

import static org.assertj.core.api.Assertions.assertThat;

import com.jordansimsmith.dynamodb.DynamoDbUtils;
import com.jordansimsmith.queue.QueueUtils;
import java.util.Base64;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.Network;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.InvocationType;
import software.amazon.awssdk.services.lambda.model.InvokeRequest;
import software.amazon.awssdk.services.lambda.model.LogType;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;

public class AuctionTrackerE2ETest {
  private static final String NETWORK_NAME = "auction-tracker-e2e";
  private static final String TRADEME_STUB_ALIAS = "trademe-stub";
  private static final Logger LOGGER = LoggerFactory.getLogger(AuctionTrackerE2ETest.class);

  private static final Network NETWORK =
      Network.builder().createNetworkCmdModifier(cmd -> cmd.withName(NETWORK_NAME)).build();

  private static final TradeMeWebsiteStubContainer tradeMeWebsiteStubContainer =
      new TradeMeWebsiteStubContainer().withNetwork(NETWORK).withNetworkAliases(TRADEME_STUB_ALIAS);

  private static final AuctionTrackerContainer auctionTrackerContainer =
      new AuctionTrackerContainer()
          .withNetwork(NETWORK)
          .withEnv("LAMBDA_DOCKER_NETWORK", NETWORK_NAME)
          .withEnv("AUCTION_TRACKER_TRADEME_BASE_URL", "http://" + TRADEME_STUB_ALIAS + ":8080");

  @BeforeAll
  static void setUpBeforeClass() {
    tradeMeWebsiteStubContainer.start();
    auctionTrackerContainer.start();
  }

  @AfterAll
  static void tearDownAfterClass() {
    auctionTrackerContainer.stop();
    tradeMeWebsiteStubContainer.stop();
    NETWORK.close();
  }

  @BeforeEach
  void setup() {
    var dynamoDbClient =
        DynamoDbClient.builder()
            .endpointOverride(auctionTrackerContainer.getLocalstackUrl())
            .build();
    var sqsClient =
        SqsClient.builder().endpointOverride(auctionTrackerContainer.getLocalstackUrl()).build();

    DynamoDbUtils.reset(dynamoDbClient);
    QueueUtils.reset(sqsClient);
  }

  @Test
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
            .maxNumberOfMessages(10)
            .waitTimeSeconds(10)
            .build();
    var messages = sqsClient.receiveMessage(receiveRequest).messages();
    assertThat(messages).isNotEmpty();

    var hasExpectedMessage =
        messages.stream()
            .map(message -> message.body())
            .anyMatch(
                messageBody ->
                    messageBody.contains("Auction Tracker Daily Digest")
                        && messageBody.contains("Titleist iron set"));
    assertThat(hasExpectedMessage).isTrue();
  }
}
