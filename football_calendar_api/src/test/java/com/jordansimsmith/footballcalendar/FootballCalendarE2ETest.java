package com.jordansimsmith.footballcalendar;

import static org.assertj.core.api.Assertions.assertThat;

import biweekly.Biweekly;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jordansimsmith.dynamodb.DynamoDbUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.Network;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.InvocationType;
import software.amazon.awssdk.services.lambda.model.InvokeRequest;

@Testcontainers
public class FootballCalendarE2ETest {
  private static final String NETWORK_NAME = "football-calendar-e2e";
  private static final String COMET_MOCK_ALIAS = "comet-mock";
  private static final String FOOTBALL_FIX_MOCK_ALIAS = "football-fix-mock";
  private static final String SUBFOOTBALL_MOCK_ALIAS = "subfootball-mock";

  private static final Network NETWORK =
      Network.builder().createNetworkCmdModifier(cmd -> cmd.withName(NETWORK_NAME)).build();

  @Container
  private static final CometMockContainer cometMockContainer =
      new CometMockContainer().withNetwork(NETWORK).withNetworkAliases(COMET_MOCK_ALIAS);

  @Container
  private static final FootballFixMockContainer footballFixMockContainer =
      new FootballFixMockContainer()
          .withNetwork(NETWORK)
          .withNetworkAliases(FOOTBALL_FIX_MOCK_ALIAS);

  @Container
  private static final SubfootballMockContainer subfootballMockContainer =
      new SubfootballMockContainer()
          .withNetwork(NETWORK)
          .withNetworkAliases(SUBFOOTBALL_MOCK_ALIAS);

  private final ObjectMapper mapper = new ObjectMapper();

  @Container
  private static final FootballCalendarContainer footballCalendarContainer =
      new FootballCalendarContainer()
          .withNetwork(NETWORK)
          .withEnv("LAMBDA_DOCKER_NETWORK", NETWORK_NAME)
          .withEnv("FOOTBALL_CALENDAR_COMET_API_URL", "http://" + COMET_MOCK_ALIAS + ":8080")
          .withEnv(
              "FOOTBALL_CALENDAR_FOOTBALL_FIX_BASE_URL",
              "http://" + FOOTBALL_FIX_MOCK_ALIAS + ":8080")
          .withEnv(
              "FOOTBALL_CALENDAR_SUBFOOTBALL_BASE_URL",
              "http://" + SUBFOOTBALL_MOCK_ALIAS + ":8080");

  @BeforeEach
  void setup() {
    var dynamoDbClient =
        DynamoDbClient.builder()
            .endpointOverride(footballCalendarContainer.getLocalstackUrl())
            .build();

    DynamoDbUtils.reset(dynamoDbClient);
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
        .contains("Bucklands Beach Bucks M5 vs Ellerslie AFC Flamingoes M")
        .contains("Lad FC vs Flamingoes")
        .contains("Man I Love Football vs Swede as Bro FC");
  }
}
