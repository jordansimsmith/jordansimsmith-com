package com.jordansimsmith.eventcalendar;

import static org.assertj.core.api.Assertions.assertThat;

import biweekly.Biweekly;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jordansimsmith.dynamodb.DynamoDbUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.Network;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.InvocationType;
import software.amazon.awssdk.services.lambda.model.InvokeRequest;

public class EventCalendarE2ETest {
  private static final String NETWORK_NAME = "event-calendar-e2e";
  private static final String GO_MEDIA_STUB_ALIAS = "gomedia-stub";
  private static final String MEETUP_STUB_ALIAS = "meetup-stub";

  private static final Network NETWORK =
      Network.builder().createNetworkCmdModifier(cmd -> cmd.withName(NETWORK_NAME)).build();

  private static final GoMediaStubContainer goMediaStubContainer =
      new GoMediaStubContainer().withNetwork(NETWORK).withNetworkAliases(GO_MEDIA_STUB_ALIAS);

  private static final MeetupStubContainer meetupStubContainer =
      new MeetupStubContainer().withNetwork(NETWORK).withNetworkAliases(MEETUP_STUB_ALIAS);

  private static final EventCalendarContainer eventCalendarContainer =
      new EventCalendarContainer()
          .withNetwork(NETWORK)
          .withEnv("LAMBDA_DOCKER_NETWORK", NETWORK_NAME)
          .withEnv("EVENT_CALENDAR_GOMEDIA_BASE_URL", "http://" + GO_MEDIA_STUB_ALIAS + ":8080")
          .withEnv("EVENT_CALENDAR_MEETUP_BASE_URL", "http://" + MEETUP_STUB_ALIAS + ":8080");

  private final ObjectMapper mapper = new ObjectMapper();

  @BeforeAll
  static void setUpBeforeClass() {
    goMediaStubContainer.start();
    meetupStubContainer.start();
    eventCalendarContainer.start();
  }

  @AfterAll
  static void tearDownAfterClass() {
    eventCalendarContainer.stop();
    meetupStubContainer.stop();
    goMediaStubContainer.stop();
    NETWORK.close();
  }

  @BeforeEach
  void setup() {
    var dynamoDbClient =
        DynamoDbClient.builder()
            .endpointOverride(eventCalendarContainer.getLocalstackUrl())
            .build();

    DynamoDbUtils.reset(dynamoDbClient);
  }

  @Test
  void shouldUpdateEventsAndProvideCalendarSubscription() throws Exception {
    // arrange
    var lambdaClient =
        LambdaClient.builder().endpointOverride(eventCalendarContainer.getLocalstackUrl()).build();

    // act - invoke update events handler
    var updateRequest =
        InvokeRequest.builder()
            .functionName("update_events_handler")
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
        .isEqualTo("-//jordansimsmith.com//Event Calendar//EN");

    // there should be at least one event in the calendar
    var events = calendar.getEvents();
    assertThat(events).isNotEmpty();

    // verify at least one event has expected properties
    var firstEvent = events.get(0);
    assertThat(firstEvent.getSummary()).isNotNull();
    assertThat(firstEvent.getDateStart()).isNotNull();
    assertThat(firstEvent.getDescription()).isNotNull();
    assertThat(firstEvent.getUrl()).isNotNull();
  }
}
