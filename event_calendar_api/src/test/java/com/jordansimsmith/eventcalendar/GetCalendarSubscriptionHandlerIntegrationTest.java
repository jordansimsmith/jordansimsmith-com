package com.jordansimsmith.eventcalendar;

import static org.assertj.core.api.Assertions.assertThat;

import biweekly.Biweekly;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.jordansimsmith.dynamodb.DynamoDbContainer;
import com.jordansimsmith.dynamodb.DynamoDbUtils;
import com.jordansimsmith.time.FakeClock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Date;
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
  private DynamoDbTable<EventCalendarItem> eventCalendarTable;
  private GetCalendarSubscriptionHandler getCalendarSubscriptionHandler;

  @Container DynamoDbContainer dynamoDbContainer = new DynamoDbContainer();

  @BeforeEach
  void setUp() {
    var factory = EventCalendarTestFactory.create(dynamoDbContainer.getEndpoint());

    fakeClock = factory.fakeClock();
    eventCalendarTable = factory.eventCalendarTable();

    DynamoDbUtils.createTable(factory.dynamoDbClient(), eventCalendarTable);

    getCalendarSubscriptionHandler = new GetCalendarSubscriptionHandler(factory);
  }

  @Test
  void handleRequestShouldReturnValidICalendarResponse() {
    // arrange
    var now = Instant.parse("2024-03-20T10:00:00Z");
    fakeClock.setTime(now);

    var warriors =
        EventCalendarItem.create(
            STADIUM_URL,
            "Warriors vs Storm",
            "https://www.aucklandstadiums.co.nz/event/warriors-storm",
            "Box office opens at 5:30PM, Gates open at 6:30PM",
            LocalDateTime.of(2024, 3, 25, 19, 30).toInstant(ZoneOffset.UTC));
    var concert =
        EventCalendarItem.create(
            STADIUM_URL,
            "Taylor Swift Concert",
            "https://www.aucklandstadiums.co.nz/event/taylor-swift",
            "Box office opens at 6PM, Gates open at 7PM",
            LocalDateTime.of(2024, 4, 15, 20, 0).toInstant(ZoneOffset.UTC));
    var cricket =
        EventCalendarItem.create(
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
}
