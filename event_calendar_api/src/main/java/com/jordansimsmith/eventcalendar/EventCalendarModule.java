package com.jordansimsmith.eventcalendar;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jordansimsmith.time.Clock;
import dagger.Module;
import dagger.Provides;
import java.net.URI;
import java.net.http.HttpClient;
import javax.inject.Singleton;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;

@Module
public class EventCalendarModule {
  @Provides
  @Singleton
  public DynamoDbTable<EventCalendarItem> eventCalendarTable(
      DynamoDbEnhancedClient dynamoDbEnhancedClient) {
    var schema = TableSchema.fromBean(EventCalendarItem.class);
    return dynamoDbEnhancedClient.table("event_calendar", schema);
  }

  @Provides
  @Singleton
  public GoMediaEventClient goMediaEventClient() {
    var baseUrl = System.getenv("EVENT_CALENDAR_GOMEDIA_BASE_URL");
    if (baseUrl == null || baseUrl.isBlank()) {
      baseUrl = "https://www.aucklandstadiums.co.nz";
    }
    return new JsoupGoMediaEventClient(URI.create(baseUrl));
  }

  @Provides
  @Singleton
  public ObjectMapper objectMapper() {
    return new ObjectMapper();
  }

  @Provides
  @Singleton
  public MeetupClient meetupClient(Clock clock, ObjectMapper objectMapper) {
    var baseUrl = System.getenv("EVENT_CALENDAR_MEETUP_BASE_URL");
    if (baseUrl == null || baseUrl.isBlank()) {
      baseUrl = "https://www.meetup.com";
    }
    var httpClient = HttpClient.newBuilder().build();
    return new HttpMeetupClient(httpClient, clock, objectMapper, URI.create(baseUrl));
  }

  @Provides
  @Singleton
  public MeetupsFactory meetupsFactory() {
    return new MeetupsFactoryImpl();
  }
}
