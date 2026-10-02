package com.jordansimsmith.pricetracker;

import static org.assertj.core.api.Assertions.assertThat;

import com.jordansimsmith.dynamodb.DynamoDbUtils;
import com.jordansimsmith.queue.QueueUtils;
import java.time.Instant;
import java.util.Collection;
import java.util.Optional;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.Network;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

public class PriceTrackerE2ETest {
  private static final String CHEMIST_WAREHOUSE_STUB_ALIAS = "chemist-warehouse-stub";
  private static final String NZ_PROTEIN_STUB_ALIAS = "nz-protein-stub";
  private static final String SPORTSFUEL_STUB_ALIAS = "sportsfuel-stub";
  private static final String VIVOBAREFOOT_STUB_ALIAS = "vivobarefoot-stub";
  private static final Network NETWORK = Network.newNetwork();

  private static final PriceTrackerWebsiteStubContainer priceTrackerWebsiteStubContainer =
      new PriceTrackerWebsiteStubContainer()
          .withNetwork(NETWORK)
          .withNetworkAliases(
              CHEMIST_WAREHOUSE_STUB_ALIAS,
              NZ_PROTEIN_STUB_ALIAS,
              SPORTSFUEL_STUB_ALIAS,
              VIVOBAREFOOT_STUB_ALIAS);

  private static final PriceTrackerContainer priceTrackerContainer =
      new PriceTrackerContainer()
          .withNetwork(NETWORK)
          .withEnv("LAMBDA_DOCKER_NETWORK", NETWORK.getId())
          .withEnv(
              "PRICE_TRACKER_CHEMIST_WAREHOUSE_BASE_URL",
              "http://" + CHEMIST_WAREHOUSE_STUB_ALIAS + ":8080")
          .withEnv("PRICE_TRACKER_NZ_PROTEIN_BASE_URL", "http://" + NZ_PROTEIN_STUB_ALIAS + ":8080")
          .withEnv("PRICE_TRACKER_SPORTSFUEL_BASE_URL", "http://" + SPORTSFUEL_STUB_ALIAS + ":8080")
          .withEnv(
              "PRICE_TRACKER_VIVOBAREFOOT_BASE_URL", "http://" + VIVOBAREFOOT_STUB_ALIAS + ":8080");

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
  void setUp() {
    var dynamoDbClient =
        DynamoDbClient.builder().endpointOverride(priceTrackerContainer.getLocalstackUrl()).build();
    var sqsClient =
        SqsClient.builder().endpointOverride(priceTrackerContainer.getLocalstackUrl()).build();
    DynamoDbUtils.reset(dynamoDbClient);
    QueueUtils.reset(sqsClient);
  }

  @Test
  void shouldProcessProductJobsBeforePublishingNetDecreaseDigest() {
    // arrange
    var dynamoDbClient =
        DynamoDbClient.builder().endpointOverride(priceTrackerContainer.getLocalstackUrl()).build();
    var enhancedClient = DynamoDbEnhancedClient.builder().dynamoDbClient(dynamoDbClient).build();
    var priceTrackerTable =
        enhancedClient.table("price_tracker", TableSchema.fromBean(PriceTrackerItem.class));
    var sqsClient =
        SqsClient.builder().endpointOverride(priceTrackerContainer.getLocalstackUrl()).build();
    var jobsQueueUrl = queueUrl(sqsClient, "price_tracker_jobs.fifo");
    var scheduledAt = Instant.now();
    var chemistProductUrl =
        "http://chemist-warehouse-stub:8080/buy/74329/inc-100-dynamic-whey-chocolate-flavour-2kg";
    var chemistProductName = "Chemist Warehouse - Dynamic Whey 2kg - Chocolate";
    var nzProteinProductUrl = "http://nz-protein-stub:8080/product/nz-whey-1kg-2-2lbs";
    var nzProteinProductName = "NZ Protein - NZ Whey 1kg (2.2lbs)";
    priceTrackerTable.putItem(
        PriceTrackerItem.create(
            chemistProductUrl, chemistProductName, Instant.ofEpochSecond(1_000_000), 1234.56));
    priceTrackerTable.putItem(
        PriceTrackerItem.create(
            nzProteinProductUrl, nzProteinProductName, Instant.ofEpochSecond(1_000_000), 4567.89));

    // act
    sendJob(sqsClient, jobsQueueUrl, "update_product", "chemist-warehouse-74329", scheduledAt);
    sendJob(sqsClient, jobsQueueUrl, "update_product", "nz-protein-nz-whey", scheduledAt);
    sendJob(sqsClient, jobsQueueUrl, "send_digest", null, scheduledAt);

    // assert
    var notification = receiveNotification(sqsClient);
    assertThat(notification).isPresent();
    assertThat(notification.orElseThrow().body())
        .contains("prices decreased")
        .contains(chemistProductName)
        .contains(chemistProductUrl)
        .contains("$1234.56 -> $52.00")
        .contains(nzProteinProductName)
        .contains(nzProteinProductUrl)
        .contains("$4567.89 -> $84.95");

    var digestCheckpointTable =
        enhancedClient.table("price_tracker", TableSchema.fromBean(DigestCheckpointItem.class));
    assertThat(
            digestCheckpointTable.getItem(
                Key.builder()
                    .partitionValue(DigestCheckpointItem.PK_VALUE)
                    .sortValue(DigestCheckpointItem.SK_VALUE)
                    .build()))
        .isNotNull();
  }

  private static String queueUrl(SqsClient sqsClient, String queueName) {
    return sqsClient.getQueueUrl(b -> b.queueName(queueName).build()).queueUrl();
  }

  private static void sendJob(
      SqsClient sqsClient, String queueUrl, String jobType, String productId, Instant scheduledAt) {
    var productIdProperty = productId == null ? "" : ",\"product_id\":\"%s\"".formatted(productId);
    sqsClient.sendMessage(
        SendMessageRequest.builder()
            .queueUrl(queueUrl)
            .messageBody(
                "{\"job_type\":\"%s\"%s,\"scheduled_at\":\"%s\"}"
                    .formatted(jobType, productIdProperty, scheduledAt))
            .messageGroupId("price-tracker")
            .build());
  }

  private static Optional<Message> receiveNotification(SqsClient sqsClient) {
    var notificationQueueUrl = queueUrl(sqsClient, "price-tracker-test-queue");
    var request =
        ReceiveMessageRequest.builder()
            .queueUrl(notificationQueueUrl)
            .maxNumberOfMessages(10)
            .waitTimeSeconds(10)
            .build();
    return IntStream.range(0, 4)
        .mapToObj(ignored -> sqsClient.receiveMessage(request).messages())
        .flatMap(Collection::stream)
        .findFirst();
  }
}
