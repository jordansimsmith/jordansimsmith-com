package com.jordansimsmith.footballcalendar;

import static org.assertj.core.api.Assertions.assertThat;

import biweekly.Biweekly;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jordansimsmith.dynamodb.DynamoDbUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.InvocationType;
import software.amazon.awssdk.services.lambda.model.InvokeRequest;

@Testcontainers
public class FootballCalendarE2ETest {
  private final ObjectMapper mapper = new ObjectMapper();

  @Container
  private static final FootballCalendarContainer footballCalendarContainer =
      new FootballCalendarContainer();

  @BeforeEach
  void setup() {
    var dynamoDbClient =
        DynamoDbClient.builder()
            .endpointOverride(footballCalendarContainer.getLocalstackUrl())
            .build();

    DynamoDbUtils.reset(dynamoDbClient);
  }

  @Test
  void shouldStartContainer() {
    assertThat(footballCalendarContainer.isRunning()).isTrue();
  }

  @Test
  @DisabledIfEnvironmentVariable(named = "CI", matches = "true")
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

    // verify at least one event has expected properties
    var firstEvent = events.get(0);
    assertThat(firstEvent.getSummary()).isNotNull();
    assertThat(firstEvent.getDateStart()).isNotNull();
    assertThat(firstEvent.getLocation()).isNotNull();
  }
}
