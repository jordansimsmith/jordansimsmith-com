package com.jordansimsmith.eventcalendar;

import static org.assertj.core.api.Assertions.assertThat;

import com.amazonaws.services.lambda.runtime.events.ScheduledEvent;
import com.jordansimsmith.dynamodb.DynamoDbUtils;
import com.jordansimsmith.testcontainers.DynamoDbContainer;
import com.jordansimsmith.time.FakeClock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
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
  private DynamoDbTable<EventCalendarItem> eventCalendarTable;

  private UpdateEventsHandler updateEventsHandler;

  @Container DynamoDbContainer dynamoDbContainer = new DynamoDbContainer();

  @BeforeEach
  void setUp() {
    var factory = EventCalendarTestFactory.create(dynamoDbContainer.getEndpoint());

    fakeClock = factory.fakeClock();
    fakeGoMediaEventClient = factory.fakeGoMediaEventClient();
    eventCalendarTable = factory.eventCalendarTable();

    DynamoDbUtils.createTable(factory.dynamoDbClient(), eventCalendarTable);

    updateEventsHandler = new UpdateEventsHandler(factory);
  }

  @Test
  void testHandleRequestSavesEventsToDb() {
    // arrange
    var testTime = Instant.parse("2024-03-20T10:00:00Z");
    fakeClock.setTime(testTime.toEpochMilli());
    var event = new ScheduledEvent();

    // create pre-existing Warriors event with old date
    var existingWarriorsEvent =
        EventCalendarItem.create(
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
    var pk = EventCalendarItem.formatPk(STADIUM_URL);
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
}
