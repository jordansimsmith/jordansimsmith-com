package com.jordansimsmith.eventcalendar;

import static org.assertj.core.api.Assertions.assertThat;

import com.amazonaws.services.lambda.runtime.events.ScheduledEvent;
import com.jordansimsmith.dynamodb.DynamoDbContainer;
import com.jordansimsmith.dynamodb.DynamoDbUtils;
import com.jordansimsmith.time.FakeClock;
import java.net.URI;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryEnhancedRequest;

@Testcontainers
public class UpdateEventsHandlerIntegrationTest {
  private static final String STADIUM_URL =
      "https://www.aucklandstadiums.co.nz/our-venues/go-media-stadium";

  private FakeClock fakeClock;
  private FakeGoMediaEventClient fakeGoMediaEventClient;
  private FakeMeetupClient fakeMeetupClient;
  private FakeMeetupsFactory fakeMeetupsFactory;
  private FakeLeinsterRugbyClient fakeLeinsterRugbyClient;
  private DynamoDbTable<EventCalendarItem> eventCalendarTable;

  private UpdateEventsHandler updateEventsHandler;

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
    fakeGoMediaEventClient = factory.fakeGoMediaEventClient();
    fakeMeetupClient = factory.fakeMeetupClient();
    fakeMeetupsFactory = factory.fakeMeetupsFactory();
    fakeLeinsterRugbyClient = factory.fakeLeinsterRugbyClient();
    eventCalendarTable = factory.eventCalendarTable();

    DynamoDbUtils.reset(factory.dynamoDbClient());
    fakeGoMediaEventClient.reset();
    fakeMeetupClient.reset();
    fakeMeetupsFactory.reset();
    fakeLeinsterRugbyClient.reset();

    updateEventsHandler = new UpdateEventsHandler(factory);
  }

  @Test
  void testHandleRequestSavesEventsToDb() {
    // arrange
    var testTime = Instant.parse("2024-03-20T10:00:00Z");
    fakeClock.setTime(testTime);
    var event = new ScheduledEvent();

    // create pre-existing Warriors event with old date
    var existingWarriorsEvent =
        EventCalendarItem.createStadiumEvent(
            STADIUM_URL,
            "Warriors vs Storm",
            "https://www.aucklandstadiums.co.nz/event/warriors-storm",
            "Box office opens at 5:30PM, Gates open at 6:30PM",
            LocalDateTime.of(2024, 3, 24, 19, 30).toInstant(ZoneOffset.UTC));
    eventCalendarTable.putItem(existingWarriorsEvent);

    var warriors =
        new GoMediaEventClient.GoMediaEvent(
            "Warriors vs Storm",
            STADIUM_URL,
            "https://www.aucklandstadiums.co.nz/event/warriors-storm",
            LocalDateTime.of(2024, 3, 25, 19, 30).toInstant(ZoneOffset.UTC),
            "Box office opens at 5:30PM, Gates open at 6:30PM");
    var concert =
        new GoMediaEventClient.GoMediaEvent(
            "Taylor Swift Concert",
            STADIUM_URL,
            "https://www.aucklandstadiums.co.nz/event/taylor-swift",
            LocalDateTime.of(2024, 4, 15, 20, 0).toInstant(ZoneOffset.UTC),
            "Box office opens at 6PM, Gates open at 7PM");
    var cricket =
        new GoMediaEventClient.GoMediaEvent(
            "Black Caps vs Australia",
            STADIUM_URL,
            "https://www.aucklandstadiums.co.nz/event/black-caps-australia",
            LocalDateTime.of(2024, 5, 1, 14, 0).toInstant(ZoneOffset.UTC),
            "Box office opens at 12PM, Gates open at 1PM");

    fakeGoMediaEventClient.addEvent(warriors);
    fakeGoMediaEventClient.addEvent(concert);
    fakeGoMediaEventClient.addEvent(cricket);

    // act
    updateEventsHandler.handleRequest(event, null);

    // assert
    var pk = EventCalendarItem.formatStadiumEventPk(STADIUM_URL);
    var query = QueryConditional.keyEqualTo(b -> b.partitionValue(pk));
    var request = QueryEnhancedRequest.builder().queryConditional(query).build();

    var items = eventCalendarTable.query(request).items().stream().toList();
    assertThat(items).hasSize(3);

    // warriors event - verify it was updated with new date
    var warriorsItem =
        items.stream()
            .filter(item -> item.getTitle().equals(warriors.title()))
            .findFirst()
            .orElseThrow();
    assertThat(warriorsItem.getEventUrl()).isEqualTo(warriors.eventUrl());
    assertThat(warriorsItem.getEventInfo()).isEqualTo(warriors.eventInfo());
    assertThat(warriorsItem.getTimestamp())
        .isEqualTo(warriors.startTime())
        .isNotEqualTo(existingWarriorsEvent.getTimestamp());

    // concert event
    var concertItem =
        items.stream()
            .filter(item -> item.getTitle().equals(concert.title()))
            .findFirst()
            .orElseThrow();
    assertThat(concertItem.getEventUrl()).isEqualTo(concert.eventUrl());
    assertThat(concertItem.getEventInfo()).isEqualTo(concert.eventInfo());
    assertThat(concertItem.getTimestamp()).isEqualTo(concert.startTime());

    // cricket event
    var cricketItem =
        items.stream()
            .filter(item -> item.getTitle().equals(cricket.title()))
            .findFirst()
            .orElseThrow();
    assertThat(cricketItem.getEventUrl()).isEqualTo(cricket.eventUrl());
    assertThat(cricketItem.getEventInfo()).isEqualTo(cricket.eventInfo());
    assertThat(cricketItem.getTimestamp()).isEqualTo(cricket.startTime());
  }

  @Test
  void handleRequestShouldDeleteEventsThatNoLongerExistInApi() {
    // arrange
    var testTime = Instant.parse("2024-03-20T10:00:00Z");
    fakeClock.setTime(testTime);
    var event = new ScheduledEvent();

    // create pre-existing events in DB
    var existingEvent1 =
        EventCalendarItem.createStadiumEvent(
            STADIUM_URL,
            "Warriors vs Storm",
            "https://www.aucklandstadiums.co.nz/event/warriors-storm",
            "Box office opens at 5:30PM, Gates open at 6:30PM",
            LocalDateTime.of(2024, 3, 25, 19, 30).toInstant(ZoneOffset.UTC));

    var existingEvent2 =
        EventCalendarItem.createStadiumEvent(
            STADIUM_URL,
            "Taylor Swift Concert",
            "https://www.aucklandstadiums.co.nz/event/taylor-swift",
            "Box office opens at 6PM, Gates open at 7PM",
            LocalDateTime.of(2024, 4, 15, 20, 0).toInstant(ZoneOffset.UTC));

    var existingEvent3 =
        EventCalendarItem.createStadiumEvent(
            STADIUM_URL,
            "Black Caps vs Australia",
            "https://www.aucklandstadiums.co.nz/event/black-caps-australia",
            "Box office opens at 12PM, Gates open at 1PM",
            LocalDateTime.of(2024, 5, 1, 14, 0).toInstant(ZoneOffset.UTC));

    eventCalendarTable.putItem(existingEvent1);
    eventCalendarTable.putItem(existingEvent2);
    eventCalendarTable.putItem(existingEvent3);

    // Only add events 1 and 2 to the API response (event 3 is now canceled/removed)
    var apiEvent1 =
        new GoMediaEventClient.GoMediaEvent(
            "Warriors vs Storm",
            STADIUM_URL,
            "https://www.aucklandstadiums.co.nz/event/warriors-storm",
            LocalDateTime.of(2024, 3, 25, 19, 30).toInstant(ZoneOffset.UTC),
            "Box office opens at 5:30PM, Gates open at 6:30PM");

    var apiEvent2 =
        new GoMediaEventClient.GoMediaEvent(
            "Taylor Swift Concert",
            STADIUM_URL,
            "https://www.aucklandstadiums.co.nz/event/taylor-swift",
            LocalDateTime.of(2024, 4, 15, 20, 0).toInstant(ZoneOffset.UTC),
            "Box office opens at 6PM, Gates open at 7PM");

    fakeGoMediaEventClient.addEvent(apiEvent1);
    fakeGoMediaEventClient.addEvent(apiEvent2);

    // act
    updateEventsHandler.handleRequest(event, null);

    // assert
    var items = eventCalendarTable.scan().items().stream().toList();

    // Should only have 2 items - the events returned by the API
    assertThat(items).hasSize(2);

    // Verify the correct events exist
    assertThat(items).anyMatch(item -> item.getEventUrl().equals(apiEvent1.eventUrl()));
    assertThat(items).anyMatch(item -> item.getEventUrl().equals(apiEvent2.eventUrl()));

    // Verify event3 was deleted
    assertThat(items).noneMatch(item -> item.getEventUrl().equals(existingEvent3.getEventUrl()));
  }

  @Test
  void handleRequestShouldProcessMeetupEvents() {
    // arrange
    var testTime = Instant.parse("2024-03-20T10:00:00Z");
    fakeClock.setTime(testTime);
    var event = new ScheduledEvent();

    var testGroupUrl = URI.create("https://www.meetup.com/test-group");
    fakeMeetupsFactory.addMeetupGroup(new MeetupsFactory.MeetupGroup(testGroupUrl));

    fakeMeetupClient.addEvent(
        testGroupUrl,
        new MeetupClient.MeetupEvent(
            "Test Meetup",
            "https://www.meetup.com/test-group",
            "https://www.meetup.com/test-group/events/123",
            Instant.parse("2025-11-22T02:00:00Z"),
            "Test Venue, Auckland"));

    // act
    updateEventsHandler.handleRequest(event, null);

    // assert
    var items = eventCalendarTable.scan().items().stream().toList();
    var meetupEvents =
        items.stream().filter(item -> item.getPk().startsWith("MEETUP_GROUP#")).toList();
    assertThat(meetupEvents).hasSize(1);
    assertThat(meetupEvents.get(0).getTitle()).isEqualTo("Test Meetup");
    assertThat(meetupEvents.get(0).getLocation()).isEqualTo("Test Venue, Auckland");
    assertThat(meetupEvents.get(0).getEventUrl())
        .isEqualTo("https://www.meetup.com/test-group/events/123");
    assertThat(meetupEvents.get(0).getTimestamp()).isEqualTo(Instant.parse("2025-11-22T02:00:00Z"));
  }

  @Test
  void handleRequestShouldProcessLeinsterFixtures() {
    // arrange
    var testTime = Instant.parse("2024-03-20T10:00:00Z");
    fakeClock.setTime(testTime);
    var event = new ScheduledEvent();

    fakeLeinsterRugbyClient.addFixture(
        new LeinsterRugbyClient.LeinsterFixture(
            "fixture-123",
            "Leinster Rugby v Harlequins",
            Instant.parse("2024-04-20T16:30:00Z"),
            "Investec Champions Cup",
            "Aviva Stadium, Dublin"));

    // act
    updateEventsHandler.handleRequest(event, null);

    // assert
    var pk = EventCalendarItem.formatSportsTeamEventPk(LeinsterRugbyClient.PUBLIC_FIXTURES_URL);
    var items =
        eventCalendarTable
            .query(QueryConditional.keyEqualTo(b -> b.partitionValue(pk)))
            .items()
            .stream()
            .toList();

    assertThat(items).hasSize(1);
    var item = items.get(0);
    assertThat(item.getTitle()).isEqualTo("Leinster Rugby v Harlequins");
    assertThat(item.getEventInfo()).isEqualTo("Investec Champions Cup");
    assertThat(item.getLocation()).isEqualTo("Aviva Stadium, Dublin");
    assertThat(item.getEventId()).isEqualTo("fixture-123");
    assertThat(item.getEventUrl()).isNull();
    assertThat(item.getTimestamp()).isEqualTo(Instant.parse("2024-04-20T16:30:00Z"));
  }
}
