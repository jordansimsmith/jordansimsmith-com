package com.jordansimsmith.eventcalendar;

import biweekly.Biweekly;
import biweekly.ICalendar;
import biweekly.component.VEvent;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Strings;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional;

public class GetCalendarSubscriptionHandler
    implements RequestHandler<APIGatewayV2HTTPEvent, APIGatewayV2HTTPResponse> {

  private static final Logger LOGGER =
      LoggerFactory.getLogger(GetCalendarSubscriptionHandler.class);

  private final DynamoDbTable<EventCalendarItem> eventCalendarTable;
  private final MeetupsFactory meetupsFactory;

  public GetCalendarSubscriptionHandler() {
    this(EventCalendarFactory.create());
  }

  @VisibleForTesting
  GetCalendarSubscriptionHandler(EventCalendarFactory factory) {
    this.eventCalendarTable = factory.eventCalendarTable();
    this.meetupsFactory = factory.meetupsFactory();
  }

  @Override
  public APIGatewayV2HTTPResponse handleRequest(APIGatewayV2HTTPEvent event, Context context) {
    try {
      return doHandleRequest(event, context);
    } catch (Exception e) {
      LOGGER.error("Error processing calendar subscription request", e);
      throw new RuntimeException(e);
    }
  }

  private APIGatewayV2HTTPResponse doHandleRequest(APIGatewayV2HTTPEvent event, Context context) {
    // create calendar
    var calendar = new ICalendar();
    calendar.setProductId("-//jordansimsmith.com//Event Calendar//EN");

    // get all events from dynamodb by querying each PK
    var allItems = new ArrayList<EventCalendarItem>();

    // query stadium events
    var stadiumPk = EventCalendarItem.formatStadiumEventPk(GoMediaEventClient.STADIUM_URL);
    allItems.addAll(
        eventCalendarTable
            .query(QueryConditional.keyEqualTo(Key.builder().partitionValue(stadiumPk).build()))
            .items()
            .stream()
            .toList());

    // query meetup events for each group
    for (var group : meetupsFactory.findMeetupGroups()) {
      var meetupPk = EventCalendarItem.formatMeetupEventPk(group.meetupGroupUrl().toString());
      allItems.addAll(
          eventCalendarTable
              .query(QueryConditional.keyEqualTo(Key.builder().partitionValue(meetupPk).build()))
              .items()
              .stream()
              .toList());
    }

    // create ical events
    for (var item : allItems) {
      var vevent = new VEvent();
      vevent.setSummary(item.getTitle());
      vevent.setDateStart(Date.from(item.getTimestamp()));
      vevent.setDescription(item.getEventInfo());
      vevent.setUrl(item.getEventUrl());
      if (!Strings.isNullOrEmpty(item.getLocation())) {
        vevent.setLocation(item.getLocation());
      }
      calendar.addEvent(vevent);
    }

    // generate ical string
    var iCalString = Biweekly.write(calendar).go();

    // create response
    var headers = new HashMap<String, String>();
    headers.put("Content-Type", "text/calendar; charset=utf-8");

    return APIGatewayV2HTTPResponse.builder()
        .withStatusCode(200)
        .withHeaders(headers)
        .withBody(iCalString)
        .build();
  }
}
