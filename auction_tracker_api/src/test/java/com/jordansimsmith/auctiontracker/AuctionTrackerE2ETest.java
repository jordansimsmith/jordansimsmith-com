package com.jordansimsmith.auctiontracker;

import static org.assertj.core.api.Assertions.assertThat;

import com.jordansimsmith.dynamodb.DynamoDbUtils;
import com.jordansimsmith.llm.OpenAiStubContainer;
import com.jordansimsmith.queue.QueueUtils;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.Network;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

public class AuctionTrackerE2ETest {
  // union of the configured judges' criteria so the one stubbed response satisfies each judge
  private static final String PASS_JUDGMENT =
      """
      {
        "mtg_cards": {"reasoning": "ok", "result": "pass"},
        "bulk_scale": {"reasoning": "ok", "result": "pass"},
        "not_basic_lands": {"reasoning": "ok", "result": "pass"},
        "fixed_collection": {"reasoning": "ok", "result": "pass"},
        "pokemon_cards": {"reasoning": "ok", "result": "pass"},
        "accepted_language": {"reasoning": "ok", "result": "pass"},
        "not_basic_energy": {"reasoning": "ok", "result": "pass"},
        "not_mega_evolution_era": {"reasoning": "ok", "result": "pass"},
        "acceptable_condition": {"reasoning": "ok", "result": "pass"},
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
    var sqsClient =
        SqsClient.builder().endpointOverride(auctionTrackerContainer.getLocalstackUrl()).build();
    var jobsQueueUrl =
        sqsClient.getQueueUrl(b -> b.queueName("auction_tracker_jobs.fifo").build()).queueUrl();
    var scheduledAt = Instant.now().plusSeconds(3600);

    // act - enqueue ordered searches and the digest behind them in the FIFO stream
    for (var searchId : List.of("mtg-bulk", "mtg-collection", "pokemon-bulk")) {
      sqsClient.sendMessage(
          SendMessageRequest.builder()
              .queueUrl(jobsQueueUrl)
              .messageBody(
                  "{\"job_type\":\"update_search\",\"search_id\":\"%s\",\"scheduled_at\":\"%s\"}"
                      .formatted(searchId, scheduledAt))
              .messageGroupId("auction-tracker")
              .build());
    }
    sqsClient.sendMessage(
        SendMessageRequest.builder()
            .queueUrl(jobsQueueUrl)
            .messageBody(
                "{\"job_type\":\"send_digest\",\"scheduled_at\":\"%s\"}".formatted(scheduledAt))
            .messageGroupId("auction-tracker")
            .build());

    // assert - verify digest message was sent to SNS/SQS
    var queueName = "auction-tracker-test-queue";
    var queueUrl = sqsClient.getQueueUrl(b -> b.queueName(queueName).build()).queueUrl();
    var receiveRequest =
        ReceiveMessageRequest.builder()
            .queueUrl(queueUrl)
            .maxNumberOfMessages(10)
            .waitTimeSeconds(20)
            .build();
    var messages = new ArrayList<Message>();
    for (var attempt = 0; attempt < 3 && messages.isEmpty(); attempt++) {
      messages.addAll(sqsClient.receiveMessage(receiveRequest).messages());
    }
    assertThat(messages).isNotEmpty();

    var hasExpectedMessage =
        messages.stream()
            .map(message -> message.body())
            .anyMatch(
                messageBody ->
                    messageBody.contains("Auction Tracker Daily Digest")
                        && messageBody.contains("Titleist iron set")
                        && messageBody.contains("Pokemon bulk collection")
                        && !messageBody.contains("Callaway iron set"));
    assertThat(hasExpectedMessage).isTrue();

    var dynamoDbClient =
        DynamoDbClient.builder()
            .endpointOverride(auctionTrackerContainer.getLocalstackUrl())
            .build();
    var storedTitles =
        dynamoDbClient.scan(b -> b.tableName("auction_tracker")).items().stream()
            .map(item -> item.get("title").s())
            .toList();
    assertThat(storedTitles)
        .contains("Titleist iron set", "Pokemon bulk collection")
        .doesNotContain("Callaway iron set");
  }

  @Test
  void shouldRoutePoisonMessageToWorkerDlqAfterFiveReceives() {
    // arrange
    var sqsClient =
        SqsClient.builder().endpointOverride(auctionTrackerContainer.getLocalstackUrl()).build();
    var jobsQueueUrl =
        sqsClient.getQueueUrl(b -> b.queueName("auction_tracker_jobs.fifo").build()).queueUrl();
    var jobsDlqUrl =
        sqsClient.getQueueUrl(b -> b.queueName("auction_tracker_jobs_dlq.fifo").build()).queueUrl();
    var poisonBody = "{\"job_type\":\"unknown\",\"scheduled_at\":\"2026-09-25T09:00:00Z\"}";

    // act
    sqsClient.sendMessage(
        SendMessageRequest.builder()
            .queueUrl(jobsQueueUrl)
            .messageBody(poisonBody)
            .messageGroupId("auction-tracker")
            .build());

    // assert
    var dlqMessage =
        IntStream.range(0, 30)
            .mapToObj(
                ignored ->
                    sqsClient
                        .receiveMessage(
                            ReceiveMessageRequest.builder()
                                .queueUrl(jobsDlqUrl)
                                .maxNumberOfMessages(1)
                                .waitTimeSeconds(1)
                                .build())
                        .messages())
            .flatMap(Collection::stream)
            .findFirst();
    assertThat(dlqMessage).isPresent();
    assertThat(dlqMessage.orElseThrow().body()).isEqualTo(poisonBody);
  }
}
