package com.jordansimsmith.eventcalendar;

import dagger.Module;
import dagger.Provides;
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
}
