package com.jordansimsmith.subfootballtracker;

import static org.assertj.core.api.Assertions.assertThat;

import com.jordansimsmith.dynamodb.DynamoDbUtils;
import com.jordansimsmith.queue.QueueUtils;
import java.time.Instant;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.Network;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryEnhancedRequest;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.InvocationType;
import software.amazon.awssdk.services.lambda.model.InvokeRequest;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;

public class SubfootballTrackerE2ETest {
  private static final String NETWORK_NAME = "subfootball-tracker-e2e";
  private static final String SUBFOOTBALL_STUB_ALIAS = "subfootball-stub";

  private static final Network NETWORK =
      Network.builder().createNetworkCmdModifier(cmd -> cmd.withName(NETWORK_NAME)).build();

  private static final SubfootballWebsiteStubContainer subfootballWebsiteStubContainer =
      new SubfootballWebsiteStubContainer()
          .withNetwork(NETWORK)
          .withNetworkAliases(SUBFOOTBALL_STUB_ALIAS);

  private static final SubfootballTrackerContainer subfootballTrackerContainer =
      new SubfootballTrackerContainer()
          .withNetwork(NETWORK)
          .withEnv("LAMBDA_DOCKER_NETWORK", NETWORK_NAME)
          .withEnv(
              "SUBFOOTBALL_TRACKER_SUBFOOTBALL_BASE_URL",
              "http://" + SUBFOOTBALL_STUB_ALIAS + ":8080");

  @BeforeAll
  static void setUpBeforeClass() {
    subfootballWebsiteStubContainer.start();
    subfootballTrackerContainer.start();
  }

  @AfterAll
  static void tearDownAfterClass() {
    subfootballTrackerContainer.stop();
    subfootballWebsiteStubContainer.stop();
    NETWORK.close();
  }

  @BeforeEach
  void setup() {
    var dynamoDbClient =
        DynamoDbClient.builder()
            .endpointOverride(subfootballTrackerContainer.getLocalstackUrl())
            .build();
    var sqsClient =
        SqsClient.builder()
            .endpointOverride(subfootballTrackerContainer.getLocalstackUrl())
            .build();

    DynamoDbUtils.reset(dynamoDbClient);
    QueueUtils.reset(sqsClient);
  }

  @Test
  void shouldUpdateRegistrationContentAndPublishNotification() {
    // arrange
    var dynamoDbClient =
        DynamoDbClient.builder()
            .endpointOverride(subfootballTrackerContainer.getLocalstackUrl())
            .build();
    var enhancedClient = DynamoDbEnhancedClient.builder().dynamoDbClient(dynamoDbClient).build();
    var subfootballTrackerTable =
        enhancedClient.table(
            "subfootball_tracker", TableSchema.fromBean(SubfootballTrackerItem.class));
    var lambdaClient =
        LambdaClient.builder()
            .endpointOverride(subfootballTrackerContainer.getLocalstackUrl())
            .build();
    var sqsClient =
        SqsClient.builder()
            .endpointOverride(subfootballTrackerContainer.getLocalstackUrl())
            .build();

    var previousSnapshot =
        SubfootballTrackerItem.create(
            SubfootballTrackerItem.Page.REGISTRATION,
            Instant.ofEpochSecond(1_000_000),
            "Old registration content");
    subfootballTrackerTable.putItem(previousSnapshot);

    // act
    var request =
        InvokeRequest.builder()
            .functionName("update_page_content_handler")
            .invocationType(InvocationType.REQUEST_RESPONSE)
            .build();
    var lambdaResponse = lambdaClient.invoke(request);
    assertThat(lambdaResponse.statusCode()).isEqualTo(200);
    assertThat(lambdaResponse.functionError())
        .withFailMessage(new String(lambdaResponse.payload().asByteArray()))
        .isNull();

    // assert
    var queueName = "subfootball-tracker-test-queue";
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
                    messageBody.contains("SUB Football registration page updated")
                        && messageBody.contains("The latest content reads:")
                        && messageBody.contains("Turf League registrations are now open.")
                        && messageBody.contains("Auckland Grammar Turf, Normanby Rd, Mt Eden"));
    assertThat(hasExpectedMessage).isTrue();

    var latestSnapshot =
        subfootballTrackerTable
            .query(
                QueryEnhancedRequest.builder()
                    .queryConditional(
                        QueryConditional.keyEqualTo(
                            Key.builder()
                                .partitionValue(
                                    SubfootballTrackerItem.formatPk(
                                        SubfootballTrackerItem.Page.REGISTRATION))
                                .build()))
                    .limit(1)
                    .scanIndexForward(false)
                    .build())
            .items()
            .stream()
            .findFirst()
            .orElse(null);

    assertThat(latestSnapshot).isNotNull();
    assertThat(latestSnapshot.getTimestamp()).isAfter(previousSnapshot.getTimestamp());
    assertThat(latestSnapshot.getContent()).contains("Turf League registrations are now open.");
    assertThat(latestSnapshot.getContent()).contains("Auckland Grammar Turf, Normanby Rd, Mt Eden");
  }
}
