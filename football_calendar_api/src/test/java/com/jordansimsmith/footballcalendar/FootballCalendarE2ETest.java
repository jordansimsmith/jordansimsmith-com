package com.jordansimsmith.footballcalendar;

import static org.assertj.core.api.Assertions.assertThat;

import biweekly.Biweekly;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jordansimsmith.dynamodb.DynamoDbUtils;
import com.jordansimsmith.queue.QueueUtils;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.Network;
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
public class FootballCalendarE2ETest {
  private static final Network NETWORK = Network.newNetwork();

  @Container
  private static final NrfStubContainer nrfStubContainer =
      new NrfStubContainer().withNetwork(NETWORK);

  @Container
  private static final FootballFixStubContainer footballFixStubContainer =
      new FootballFixStubContainer().withNetwork(NETWORK);

  @Container
  private static final SubfootballStubContainer subfootballStubContainer =
      new SubfootballStubContainer().withNetwork(NETWORK);

  private final ObjectMapper mapper = new ObjectMapper();

  @Container
  private static final FootballCalendarContainer footballCalendarContainer =
      new FootballCalendarContainer()
          .withNetwork(NETWORK)
          .withEnv("LAMBDA_DOCKER_NETWORK", NETWORK.getId())
          .withEnv("FOOTBALL_CALENDAR_NRF_API_URL", nrfStubContainer.getEndpoint().toString())
          .withEnv(
              "FOOTBALL_CALENDAR_FOOTBALL_FIX_BASE_URL",
              footballFixStubContainer.getEndpoint().toString())
          .withEnv(
              "FOOTBALL_CALENDAR_SUBFOOTBALL_BASE_URL",
              subfootballStubContainer.getEndpoint().toString());

  @BeforeEach
  void setup() {
    var dynamoDbClient =
        DynamoDbClient.builder()
            .endpointOverride(footballCalendarContainer.getLocalstackUrl())
            .build();
    var sqsClient =
        SqsClient.builder().endpointOverride(footballCalendarContainer.getLocalstackUrl()).build();

    DynamoDbUtils.reset(dynamoDbClient);
    QueueUtils.reset(sqsClient);
  }

  @Test
  void shouldUpdateFixturesAndProvideCalendarSubscription() throws Exception {
    // arrange
    var lambdaClient =
        LambdaClient.builder()
            .endpointOverride(footballCalendarContainer.getLocalstackUrl())
            .build();

    // act - invoke update fixtures handler
    var updateRequest =
        InvokeRequest.builder()
            .functionName("update_fixtures_handler")
            .invocationType(InvocationType.REQUEST_RESPONSE)
            .build();
    var updateResponse = lambdaClient.invoke(updateRequest);
    assertThat(updateResponse.statusCode()).isEqualTo(200);
    assertThat(updateResponse.functionError())
        .withFailMessage(new String(updateResponse.payload().asByteArray()))
        .isNull();

    // act - invoke get calendar subscription handler
    var subscriptionRequest =
        InvokeRequest.builder()
            .functionName("get_calendar_subscription_handler")
            .invocationType(InvocationType.REQUEST_RESPONSE)
            .build();
    var subscriptionResponse = lambdaClient.invoke(subscriptionRequest);
    assertThat(subscriptionResponse.statusCode()).isEqualTo(200);
    assertThat(subscriptionResponse.functionError())
        .withFailMessage(new String(subscriptionResponse.payload().asByteArray()))
        .isNull();

    // assert - verify calendar contains events
    var responseBody = new String(subscriptionResponse.payload().asByteArray());
    var jsonResponse = mapper.readTree(responseBody);
    var iCalContent = jsonResponse.get("body").asText();

    var calendar = Biweekly.parse(iCalContent).first();
    assertThat(calendar).isNotNull();
    assertThat(calendar.getProductId().getValue())
        .isEqualTo("-//jordansimsmith.com//Football Calendar//EN");

    // there should be at least one event in the calendar
    var events = calendar.getEvents();
    assertThat(events).isNotEmpty();
    assertThat(events)
        .allSatisfy(
            event -> {
              assertThat(event.getSummary()).isNotNull();
              assertThat(event.getSummary().getValue()).isNotBlank();
              assertThat(event.getDateStart()).isNotNull();
              assertThat(event.getLocation()).isNotNull();
              assertThat(event.getLocation().getValue()).isNotBlank();
            });

    var summaries = events.stream().map(event -> event.getSummary().getValue()).toList();
    assertThat(summaries)
        .contains("Bucklands Beach AFC Dusties vs Ellerslie AFC Flamingos")
        .contains("Lad FC vs Flamingoes")
        .contains("Man I Love Football vs Swede as Bro FC");
  }

  @Test
  void shouldSendNotificationWhenUpcomingFixtureRemoved() throws Exception {
    // arrange
    var dynamoDbClient =
        DynamoDbClient.builder()
            .endpointOverride(footballCalendarContainer.getLocalstackUrl())
            .build();
    var enhancedClient = DynamoDbEnhancedClient.builder().dynamoDbClient(dynamoDbClient).build();
    var footballCalendarTable =
        enhancedClient.table("football_calendar", TableSchema.fromBean(FootballCalendarItem.class));
    var lambdaClient =
        LambdaClient.builder()
            .endpointOverride(footballCalendarContainer.getLocalstackUrl())
            .build();
    var sqsClient =
        SqsClient.builder().endpointOverride(footballCalendarContainer.getLocalstackUrl()).build();

    // pre-seed a Flamingos fixture with upcoming timestamp and a match ID not in mock data
    var upcomingTimestamp = Instant.now().plus(Duration.ofDays(3));
    var existingFixture =
        FootballCalendarItem.create(
            "Flamingos",
            "fixture-to-be-removed",
            "Ellerslie AFC Flamingos",
            "Auckland City FC",
            upcomingTimestamp,
            "Kiwitea Street",
            "Kiwitea Street, Auckland",
            null,
            null,
            "Scheduled");
    footballCalendarTable.putItem(existingFixture);

    // act
    var updateRequest =
        InvokeRequest.builder()
            .functionName("update_fixtures_handler")
            .invocationType(InvocationType.REQUEST_RESPONSE)
            .build();
    var updateResponse = lambdaClient.invoke(updateRequest);
    assertThat(updateResponse.statusCode()).isEqualTo(200);
    assertThat(updateResponse.functionError())
        .withFailMessage(new String(updateResponse.payload().asByteArray()))
        .isNull();

    // assert
    var queueName = "football-calendar-test-queue";
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
                    messageBody.contains("Football calendar fixture updated")
                        && messageBody.contains("REMOVED")
                        && messageBody.contains("Flamingos")
                        && messageBody.contains("Kiwitea Street"));
    assertThat(hasExpectedMessage).isTrue();
  }
}
