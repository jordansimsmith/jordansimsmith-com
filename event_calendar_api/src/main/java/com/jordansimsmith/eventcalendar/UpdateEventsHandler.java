package com.jordansimsmith.eventcalendar;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.ScheduledEvent;
import com.google.common.annotations.VisibleForTesting;
import java.util.stream.Collectors;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional;

public class UpdateEventsHandler implements RequestHandler<ScheduledEvent, Void> {
  private final DynamoDbTable<EventCalendarItem> eventCalendarTable;
  private final GoMediaEventClient goMediaEventClient;

  public UpdateEventsHandler() {
    this(EventCalendarFactory.create());
  }

  @VisibleForTesting
  UpdateEventsHandler(EventCalendarFactory factory) {
    this.eventCalendarTable = factory.eventCalendarTable();
    this.goMediaEventClient = factory.goMediaEventClient();
  }

  @Override
  public Void handleRequest(ScheduledEvent event, Context context) {
    try {
      return doHandleRequest(event, context);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  private Void doHandleRequest(ScheduledEvent event, Context context) {
    // get events from the website
    var events = goMediaEventClient.getEvents();

    // Fetch all existing events from DynamoDB for this stadium
    var existingEvents =
        eventCalendarTable
            .query(
                QueryConditional.keyEqualTo(
                    Key.builder()
                        .partitionValue(EventCalendarItem.formatPk(GoMediaEventClient.STADIUM_URL))
                        .build()))
            .items()
            .stream()
            .toList();

    // Create a set of event URLs from the current API response to use for comparison
    var currentEventUrls =
        events.stream().map(GoMediaEventClient.GoMediaEvent::eventUrl).collect(Collectors.toSet());

    // Delete events that no longer exist in the API response
    for (var existingEvent : existingEvents) {
      if (!currentEventUrls.contains(existingEvent.getEventUrl())) {
        eventCalendarTable.deleteItem(existingEvent);
      }
    }

    // save/update each event to dynamodb
    for (var goMediaEvent : events) {
      var item =
          EventCalendarItem.create(
              goMediaEvent.stadiumUrl(),
              goMediaEvent.title(),
              goMediaEvent.eventUrl(),
              goMediaEvent.eventInfo(),
              goMediaEvent.startTime());
      eventCalendarTable.putItem(item);
    }

    return null;
  }
}
