package com.jordansimsmith.eventcalendar;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.ScheduledEvent;
import com.google.common.annotations.VisibleForTesting;
import com.jordansimsmith.time.Clock;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;

public class UpdateEventsHandler implements RequestHandler<ScheduledEvent, Void> {
  private final Clock clock;
  private final DynamoDbTable<EventCalendarItem> eventCalendarTable;
  private final GoMediaEventClient goMediaEventClient;

  public UpdateEventsHandler() {
    this(EventCalendarFactory.create());
  }

  @VisibleForTesting
  UpdateEventsHandler(EventCalendarFactory factory) {
    this.clock = factory.clock();
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
    var now = clock.now();

    // get events from the website
    var events = goMediaEventClient.getEvents();

    // save each event to dynamodb
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
