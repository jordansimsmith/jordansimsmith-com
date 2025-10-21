package com.jordansimsmith.footballcalendar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import biweekly.Biweekly;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.jordansimsmith.dynamodb.DynamoDbContainer;
import com.jordansimsmith.dynamodb.DynamoDbUtils;
import java.time.Instant;
import java.util.Date;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;

@Testcontainers
public class GetCalendarSubscriptionHandlerIntegrationTest {

  private FakeTeamsFactory fakeTeamsFactory;
  private DynamoDbTable<FootballCalendarItem> footballCalendarTable;
  private GetCalendarSubscriptionHandler getCalendarSubscriptionHandler;

  @Container DynamoDbContainer dynamoDbContainer = new DynamoDbContainer();

  @BeforeEach
  void setUp() {
    var factory = FootballCalendarTestFactory.create(dynamoDbContainer.getEndpoint());

    fakeTeamsFactory = factory.fakeTeamsFactory();
    footballCalendarTable = factory.footballCalendarTable();

    DynamoDbUtils.createTable(factory.dynamoDbClient(), footballCalendarTable);

    getCalendarSubscriptionHandler = new GetCalendarSubscriptionHandler(factory);
  }

  @Test
  void shouldReturnEmptyCalendarWhenNoFixtures() {
    // arrange
    var event = APIGatewayV2HTTPEvent.builder().build();

    // act
    var response = getCalendarSubscriptionHandler.handleRequest(event, null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(200);
    assertThat(response.getHeaders().get("Content-Type")).isEqualTo("text/calendar; charset=utf-8");

    var calendar = Biweekly.parse(response.getBody()).first();
    assertThat(calendar).isNotNull();
    assertThat(calendar.getProductId().getValue())
        .isEqualTo("-//jordansimsmith.com//Football Calendar//EN");
    assertThat(calendar.getEvents()).isEmpty();
  }

  @Test
  void shouldReturnFixturesInCalendar() {
    // arrange
    fakeTeamsFactory.addTeam(
        new TeamsFactory.NorthernRegionalFootballTeam(
            "Flamingos", "flamingo", "44838", "2716594877", "2025"));

    var fixture =
        FootballCalendarItem.create(
            "Flamingos",
            "2716942185",
            "Bucklands Beach Bucks M5",
            "Ellerslie AFC Flamingoes M",
            Instant.parse("2025-04-05T15:00:00Z"),
            "Lloyd Elsmore Park 2",
            "2 Bells Avenue",
            -36.9053315,
            174.8997797,
            "CONFIRMED");
    footballCalendarTable.putItem(fixture);

    var event = APIGatewayV2HTTPEvent.builder().build();

    // act
    var response = getCalendarSubscriptionHandler.handleRequest(event, null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(200);

    var calendar = Biweekly.parse(response.getBody()).first();
    assertThat(calendar).isNotNull();
    assertThat(calendar.getEvents()).hasSize(1);

    var calendarEvent = calendar.getEvents().get(0);
    assertThat(calendarEvent.getSummary().getValue())
        .isEqualTo("Bucklands Beach Bucks M5 vs Ellerslie AFC Flamingoes M");
    assertThat(calendarEvent.getDateStart().getValue())
        .isEqualTo(Date.from(Instant.parse("2025-04-05T15:00:00Z")));
    assertThat(calendarEvent.getLocation().getValue())
        .isEqualTo("Lloyd Elsmore Park 2, 2 Bells Avenue");
    assertThat(calendarEvent.getDescription().getValue()).contains("CONFIRMED");
    assertThat(calendarEvent.getGeo().getLatitude()).isCloseTo(-36.9053315, within(0.00001));
    assertThat(calendarEvent.getGeo().getLongitude()).isCloseTo(174.8997797, within(0.00001));
  }
}
