package com.jordansimsmith.eventcalendar;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jordansimsmith.time.Clock;
import dagger.Module;
import dagger.Provides;
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
    return new JsoupGoMediaEventClient();
  }

  @Provides
  @Singleton
  public MeetupClient meetupClient(Clock clock) {
    var httpClient = HttpClient.newBuilder().build();
    var objectMapper = new ObjectMapper();
    return new HttpMeetupClient(httpClient, clock, objectMapper);
  }

  @Provides
  @Singleton
  public MeetupsFactory meetupsFactory() {
    return new MeetupsFactoryImpl();
  }
}
