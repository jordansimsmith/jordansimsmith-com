package com.jordansimsmith.eventcalendar;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.ScheduledEvent;
import com.google.common.annotations.VisibleForTesting;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional;

public class UpdateEventsHandler implements RequestHandler<ScheduledEvent, Void> {
  private static final Logger logger = LoggerFactory.getLogger(UpdateEventsHandler.class);

  private final DynamoDbTable<EventCalendarItem> eventCalendarTable;
  private final GoMediaEventClient goMediaEventClient;
  private final MeetupClient meetupClient;
  private final MeetupsFactory meetupsFactory;

  public UpdateEventsHandler() {
    this(EventCalendarFactory.create());
  }

  @VisibleForTesting
  UpdateEventsHandler(EventCalendarFactory factory) {
    this.eventCalendarTable = factory.eventCalendarTable();
    this.goMediaEventClient = factory.goMediaEventClient();
    this.meetupClient = factory.meetupClient();
    this.meetupsFactory = factory.meetupsFactory();
  }

  @Override
  public Void handleRequest(ScheduledEvent event, Context context) {
    try {
      return doHandleRequest(event, context);
    } catch (Exception e) {
      logger.error("Error processing event calendar update", e);
      throw new RuntimeException(e);
    }
  }

  private Void doHandleRequest(ScheduledEvent event, Context context) {
    // find and combine all events from various sources
    var allEvents = new ArrayList<EventCalendarItem>();
    allEvents.addAll(findGoMediaEvents());
    allEvents.addAll(findMeetupEvents());
    var eventsByPk = allEvents.stream().collect(Collectors.groupingBy(EventCalendarItem::getPk));

    // process each PK separately
    for (var entry : eventsByPk.entrySet()) {
      var pk = entry.getKey();
      var events = entry.getValue();

      // fetch all existing events for this PK from DynamoDB
      var existingEvents =
          eventCalendarTable
              .query(QueryConditional.keyEqualTo(Key.builder().partitionValue(pk).build()))
              .items()
              .stream()
              .toList();

      // create a set of event URLs from the API response to use for comparison
      var currentEventUrls =
          events.stream().map(EventCalendarItem::getEventUrl).collect(Collectors.toSet());

      // delete events that no longer exist in the API response
      for (var existingEvent : existingEvents) {
        if (!currentEventUrls.contains(existingEvent.getEventUrl())) {
          eventCalendarTable.deleteItem(existingEvent);
        }
      }

      // save/update each event to dynamodb
      for (var item : events) {
        eventCalendarTable.putItem(item);
      }
    }

    return null;
  }

  private List<EventCalendarItem> findGoMediaEvents() {
    var events = goMediaEventClient.getEvents();

    return events.stream()
        .map(
            goMediaEvent ->
                EventCalendarItem.createStadiumEvent(
                    goMediaEvent.stadiumUrl(),
                    goMediaEvent.title(),
                    goMediaEvent.eventUrl(),
                    goMediaEvent.eventInfo(),
                    goMediaEvent.startTime()))
        .toList();
  }

  private List<EventCalendarItem> findMeetupEvents() {
    var allEvents = new ArrayList<EventCalendarItem>();
    var meetupGroups = meetupsFactory.findMeetupGroups();

    for (var group : meetupGroups) {
      var meetupEvents = meetupClient.getEvents(group.meetupGroupUrl());

      for (var meetupEvent : meetupEvents) {
        var item =
            EventCalendarItem.createMeetupEvent(
                group.meetupGroupUrl().toString(),
                meetupEvent.title(),
                meetupEvent.eventUrl(),
                meetupEvent.startTime(),
                meetupEvent.location());
        allEvents.add(item);
      }
    }

    return allEvents;
  }
}
