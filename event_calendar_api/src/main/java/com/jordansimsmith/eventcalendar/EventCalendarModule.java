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
  public ObjectMapper objectMapper() {
    return new ObjectMapper();
  }

  @Provides
  @Singleton
  public MeetupClient meetupClient(Clock clock, ObjectMapper objectMapper) {
    var httpClient = HttpClient.newBuilder().build();
    return new HttpMeetupClient(httpClient, clock, objectMapper);
  }

  @Provides
  @Singleton
  public MeetupsFactory meetupsFactory() {
    return new MeetupsFactoryImpl();
  }
}
