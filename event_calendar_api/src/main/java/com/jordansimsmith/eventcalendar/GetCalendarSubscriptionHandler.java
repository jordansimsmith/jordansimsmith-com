package com.jordansimsmith.eventcalendar;

import biweekly.Biweekly;
import biweekly.ICalendar;
import biweekly.component.VEvent;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import com.google.common.annotations.VisibleForTesting;
import java.util.Date;
import java.util.HashMap;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryEnhancedRequest;

public class GetCalendarSubscriptionHandler
    implements RequestHandler<APIGatewayV2HTTPEvent, APIGatewayV2HTTPResponse> {

  private final DynamoDbTable<EventCalendarItem> eventCalendarTable;

  public GetCalendarSubscriptionHandler() {
    this(EventCalendarFactory.create());
  }

  @VisibleForTesting
  GetCalendarSubscriptionHandler(EventCalendarFactory factory) {
    this.eventCalendarTable = factory.eventCalendarTable();
  }

  @Override
  public APIGatewayV2HTTPResponse handleRequest(APIGatewayV2HTTPEvent event, Context context) {
    try {
      return doHandleRequest(event, context);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  private APIGatewayV2HTTPResponse doHandleRequest(APIGatewayV2HTTPEvent event, Context context) {
    // create calendar
    var calendar = new ICalendar();
    calendar.setProductId("-//jordansimsmith.com//Event Calendar//EN");

    // get all events from dynamodb for the stadium
    var pk = EventCalendarItem.formatPk(GoMediaEventClient.STADIUM_URL);
    var query = QueryConditional.keyEqualTo(b -> b.partitionValue(pk));
    var request = QueryEnhancedRequest.builder().queryConditional(query).build();

    // add each event to the calendar
    for (var item : eventCalendarTable.query(request).items()) {
      var vevent = new VEvent();
      vevent.setSummary(item.getTitle());
      vevent.setDateStart(Date.from(item.getTimestamp()));
      vevent.setDescription(item.getEventInfo());
      vevent.setUrl(item.getEventUrl());
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
