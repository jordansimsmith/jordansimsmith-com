package com.jordansimsmith.auctiontracker;

import static org.assertj.core.api.Assertions.assertThat;

import com.jordansimsmith.dynamodb.DynamoDbUtils;
import com.jordansimsmith.llm.OpenAiStubContainer;
import com.jordansimsmith.queue.QueueUtils;
import java.util.Base64;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.Network;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.InvocationType;
import software.amazon.awssdk.services.lambda.model.InvokeRequest;
import software.amazon.awssdk.services.lambda.model.LogType;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;

public class AuctionTrackerE2ETest {
  private static final Logger LOGGER = LoggerFactory.getLogger(AuctionTrackerE2ETest.class);

  // union of the MTG and RAM judges' criteria so the one stubbed response satisfies both judges
  private static final String PASS_JUDGMENT =
      """
      {
        "mtg_cards": {"reasoning": "ok", "result": "pass"},
        "bulk_scale": {"reasoning": "ok", "result": "pass"},
        "not_basic_lands": {"reasoning": "ok", "result": "pass"},
        "not_universes_beyond": {"reasoning": "ok", "result": "pass"},
        "civilian_seller": {"reasoning": "ok", "result": "pass"},
        "fixed_collection": {"reasoning": "ok", "result": "pass"},
        "trident_z_family": {"reasoning": "ok", "result": "pass"},
        "ddr4": {"reasoning": "ok", "result": "pass"},
        "kit_2x16gb": {"reasoning": "ok", "result": "pass"},
        "speed_3200": {"reasoning": "ok", "result": "pass"},
        "timings_cl16": {"reasoning": "ok", "result": "pass"},
        "desktop_udimm": {"reasoning": "ok", "result": "pass"}
      }
      """;

  private static final Network NETWORK = Network.newNetwork();

  private static final TradeMeWebsiteStubContainer tradeMeWebsiteStubContainer =
      new TradeMeWebsiteStubContainer().withNetwork(NETWORK);

  private static final OpenAiStubContainer openAiStubContainer =
      new OpenAiStubContainer().withResponseContent(PASS_JUDGMENT).withNetwork(NETWORK);

  private static final AuctionTrackerContainer auctionTrackerContainer =
      new AuctionTrackerContainer()
          .withNetwork(NETWORK)
          .withEnv("LAMBDA_DOCKER_NETWORK", NETWORK.getId())
          .withEnv(
              "AUCTION_TRACKER_TRADEME_BASE_URL",
              tradeMeWebsiteStubContainer.getEndpoint().toString())
          .withEnv("AUCTION_TRACKER_OPENAI_BASE_URL", openAiStubContainer.getEndpoint().toString());

  @BeforeAll
  static void setUpBeforeClass() {
    tradeMeWebsiteStubContainer.start();
    openAiStubContainer.start();
    auctionTrackerContainer.start();
  }

  @AfterAll
  static void tearDownAfterClass() {
    auctionTrackerContainer.stop();
    openAiStubContainer.stop();
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
            .payload(SdkBytes.fromUtf8String("{}"))
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
            .payload(SdkBytes.fromUtf8String("{}"))
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
