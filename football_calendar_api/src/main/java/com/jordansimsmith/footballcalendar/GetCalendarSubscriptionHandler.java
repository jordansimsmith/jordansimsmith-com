package com.jordansimsmith.footballcalendar;

import biweekly.Biweekly;
import biweekly.ICalendar;
import biweekly.component.VEvent;
import biweekly.property.Geo;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Strings;
import java.util.Date;
import java.util.HashMap;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional;

public class GetCalendarSubscriptionHandler
    implements RequestHandler<APIGatewayV2HTTPEvent, APIGatewayV2HTTPResponse> {

  private static final String ELLERSLIE_FLAMINGOS = "Flamingos";

  private final DynamoDbTable<FootballCalendarItem> footballCalendarTable;

  public GetCalendarSubscriptionHandler() {
    this(FootballCalendarFactory.create());
  }

  @VisibleForTesting
  GetCalendarSubscriptionHandler(FootballCalendarFactory factory) {
    this.footballCalendarTable = factory.footballCalendarTable();
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
    calendar.setProductId("-//jordansimsmith.com//Football Calendar//EN");

    // Query for all fixtures for the Flamingos team
    var queryConditional =
        QueryConditional.keyEqualTo(
            Key.builder()
                .partitionValue(FootballCalendarItem.formatPk(ELLERSLIE_FLAMINGOS))
                .build());

    for (var item : footballCalendarTable.query(queryConditional).items()) {
      var vevent = new VEvent();

      // set match title as summary (home vs away)
      vevent.setSummary(String.format("%s vs %s", item.getHomeTeam(), item.getAwayTeam()));

      // set match date
      vevent.setDateStart(Date.from(item.getTimestamp()));

      // set location
      String location = item.getVenue();
      if (!Strings.isNullOrEmpty(item.getAddress())) {
        location += ", " + item.getAddress();
      }
      vevent.setLocation(location);

      // set geo coordinates
      if (item.getLatitude() != null && item.getLongitude() != null) {
        vevent.setGeo(new Geo(item.getLatitude(), item.getLongitude()));
      }

      // set description with status, only if status is available
      if (!Strings.isNullOrEmpty(item.getStatus())) {
        vevent.setDescription("Status: " + item.getStatus());
      }

      // add event to calendar
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
