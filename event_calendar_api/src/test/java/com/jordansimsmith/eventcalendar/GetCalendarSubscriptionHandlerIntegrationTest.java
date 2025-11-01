package com.jordansimsmith.eventcalendar;

import static org.assertj.core.api.Assertions.assertThat;

import biweekly.Biweekly;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.jordansimsmith.dynamodb.DynamoDbContainer;
import com.jordansimsmith.dynamodb.DynamoDbUtils;
import com.jordansimsmith.time.FakeClock;
import java.net.URI;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Date;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;

@Testcontainers
public class GetCalendarSubscriptionHandlerIntegrationTest {
  private static final String STADIUM_URL =
      "https://www.aucklandstadiums.co.nz/our-venues/go-media-stadium";

  private FakeClock fakeClock;
  private FakeMeetupsFactory fakeMeetupsFactory;
  private DynamoDbTable<EventCalendarItem> eventCalendarTable;
  private GetCalendarSubscriptionHandler getCalendarSubscriptionHandler;

  @Container private static final DynamoDbContainer dynamoDbContainer = new DynamoDbContainer();

  @BeforeAll
  static void setUpBeforeClass() {
    var factory = EventCalendarTestFactory.create(dynamoDbContainer.getEndpoint());
    var table = factory.eventCalendarTable();
    DynamoDbUtils.createTable(factory.dynamoDbClient(), table);
  }

  @BeforeEach
  void setUp() {
    var factory = EventCalendarTestFactory.create(dynamoDbContainer.getEndpoint());

    fakeClock = factory.fakeClock();
    fakeMeetupsFactory = factory.fakeMeetupsFactory();
    eventCalendarTable = factory.eventCalendarTable();

    DynamoDbUtils.reset(factory.dynamoDbClient());

    getCalendarSubscriptionHandler = new GetCalendarSubscriptionHandler(factory);
  }

  @Test
  void handleRequestShouldReturnValidICalendarResponse() {
    // arrange
    var now = Instant.parse("2024-03-20T10:00:00Z");
    fakeClock.setTime(now);

    var warriors =
        EventCalendarItem.createStadiumEvent(
            STADIUM_URL,
            "Warriors vs Storm",
            "https://www.aucklandstadiums.co.nz/event/warriors-storm",
            "Box office opens at 5:30PM, Gates open at 6:30PM",
            LocalDateTime.of(2024, 3, 25, 19, 30).toInstant(ZoneOffset.UTC));
    var concert =
        EventCalendarItem.createStadiumEvent(
            STADIUM_URL,
            "Taylor Swift Concert",
            "https://www.aucklandstadiums.co.nz/event/taylor-swift",
            "Box office opens at 6PM, Gates open at 7PM",
            LocalDateTime.of(2024, 4, 15, 20, 0).toInstant(ZoneOffset.UTC));
    var cricket =
        EventCalendarItem.createStadiumEvent(
            STADIUM_URL,
            "Black Caps vs Australia",
            "https://www.aucklandstadiums.co.nz/event/black-caps-australia",
            "Box office opens at 12PM, Gates open at 1PM",
            LocalDateTime.of(2024, 5, 1, 14, 0).toInstant(ZoneOffset.UTC));

    eventCalendarTable.putItem(warriors);
    eventCalendarTable.putItem(concert);
    eventCalendarTable.putItem(cricket);

    var event = new APIGatewayV2HTTPEvent();

    // act
    var response = getCalendarSubscriptionHandler.handleRequest(event, null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(200);
    assertThat(response.getHeaders()).containsEntry("Content-Type", "text/calendar; charset=utf-8");

    var calendar = Biweekly.parse(response.getBody()).first();
    assertThat(calendar).isNotNull();
    assertThat(calendar.getProductId().getValue())
        .isEqualTo("-//jordansimsmith.com//Event Calendar//EN");

    var events = calendar.getEvents();
    assertThat(events).hasSize(3);

    // verify events are present (order doesn't matter)
    assertThat(events)
        .anySatisfy(
            event1 -> {
              assertThat(event1.getSummary().getValue()).isEqualTo("Warriors vs Storm");
              assertThat(event1.getDateStart().getValue())
                  .isEqualTo(Date.from(warriors.getTimestamp()));
              assertThat(event1.getDescription().getValue())
                  .isEqualTo("Box office opens at 5:30PM, Gates open at 6:30PM");
              assertThat(event1.getUrl().getValue())
                  .isEqualTo("https://www.aucklandstadiums.co.nz/event/warriors-storm");
            });

    assertThat(events)
        .anySatisfy(
            event1 -> {
              assertThat(event1.getSummary().getValue()).isEqualTo("Taylor Swift Concert");
              assertThat(event1.getDateStart().getValue())
                  .isEqualTo(Date.from(concert.getTimestamp()));
              assertThat(event1.getDescription().getValue())
                  .isEqualTo("Box office opens at 6PM, Gates open at 7PM");
              assertThat(event1.getUrl().getValue())
                  .isEqualTo("https://www.aucklandstadiums.co.nz/event/taylor-swift");
            });

    assertThat(events)
        .anySatisfy(
            event1 -> {
              assertThat(event1.getSummary().getValue()).isEqualTo("Black Caps vs Australia");
              assertThat(event1.getDateStart().getValue())
                  .isEqualTo(Date.from(cricket.getTimestamp()));
              assertThat(event1.getDescription().getValue())
                  .isEqualTo("Box office opens at 12PM, Gates open at 1PM");
              assertThat(event1.getUrl().getValue())
                  .isEqualTo("https://www.aucklandstadiums.co.nz/event/black-caps-australia");
            });
  }

  @Test
  void handleRequestShouldReturnMeetupEventsInResponse() {
    // arrange
    var now = Instant.parse("2024-03-20T10:00:00Z");
    fakeClock.setTime(now);

    var testGroupUrl = URI.create("https://www.meetup.com/test-group");
    fakeMeetupsFactory.addMeetupGroup(new MeetupsFactory.MeetupGroup(testGroupUrl));

    var meetup1 =
        EventCalendarItem.createMeetupEvent(
            testGroupUrl.toString(),
            "Japanese English Exchange",
            "https://www.meetup.com/test-group/events/123",
            Instant.parse("2024-03-22T08:00:00Z"),
            "The Occidental, Auckland");
    var meetup2 =
        EventCalendarItem.createMeetupEvent(
            testGroupUrl.toString(),
            "Language Practice Night",
            "https://www.meetup.com/test-group/events/456",
            Instant.parse("2024-03-29T08:00:00Z"),
            "Golden Dawn, Auckland");

    eventCalendarTable.putItem(meetup1);
    eventCalendarTable.putItem(meetup2);

    var event = new APIGatewayV2HTTPEvent();

    // act
    var response = getCalendarSubscriptionHandler.handleRequest(event, null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(200);
    assertThat(response.getHeaders()).containsEntry("Content-Type", "text/calendar; charset=utf-8");

    var calendar = Biweekly.parse(response.getBody()).first();
    assertThat(calendar).isNotNull();

    var events = calendar.getEvents();
    assertThat(events).hasSize(2);

    assertThat(events)
        .anySatisfy(
            event1 -> {
              assertThat(event1.getSummary().getValue()).isEqualTo("Japanese English Exchange");
              assertThat(event1.getDateStart().getValue())
                  .isEqualTo(Date.from(meetup1.getTimestamp()));
              assertThat(event1.getLocation().getValue()).isEqualTo("The Occidental, Auckland");
              assertThat(event1.getUrl().getValue())
                  .isEqualTo("https://www.meetup.com/test-group/events/123");
            });

    assertThat(events)
        .anySatisfy(
            event1 -> {
              assertThat(event1.getSummary().getValue()).isEqualTo("Language Practice Night");
              assertThat(event1.getDateStart().getValue())
                  .isEqualTo(Date.from(meetup2.getTimestamp()));
              assertThat(event1.getLocation().getValue()).isEqualTo("Golden Dawn, Auckland");
              assertThat(event1.getUrl().getValue())
                  .isEqualTo("https://www.meetup.com/test-group/events/456");
            });
  }
}
