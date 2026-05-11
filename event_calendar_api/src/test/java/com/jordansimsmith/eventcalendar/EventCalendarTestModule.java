package com.jordansimsmith.eventcalendar;

import dagger.Module;
import dagger.Provides;
import javax.inject.Singleton;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;

@Module
public class EventCalendarTestModule {
  @Provides
  @Singleton
  DynamoDbTable<EventCalendarItem> eventCalendarTable(
      DynamoDbEnhancedClient dynamoDbEnhancedClient) {
    var schema = TableSchema.fromBean(EventCalendarItem.class);
    return dynamoDbEnhancedClient.table("event_calendar", schema);
  }

  @Provides
  @Singleton
  FakeGoMediaEventClient fakeGoMediaEventClient() {
    return new FakeGoMediaEventClient();
  }

  @Provides
  @Singleton
  GoMediaEventClient goMediaEventClient(FakeGoMediaEventClient fakeGoMediaEventClient) {
    return fakeGoMediaEventClient;
  }

  @Provides
  @Singleton
  FakeMeetupClient fakeMeetupClient() {
    return new FakeMeetupClient();
  }

  @Provides
  @Singleton
  MeetupClient meetupClient(FakeMeetupClient fakeMeetupClient) {
    return fakeMeetupClient;
  }

  @Provides
  @Singleton
  FakeMeetupsFactory fakeMeetupsFactory() {
    return new FakeMeetupsFactory();
  }

  @Provides
  @Singleton
  MeetupsFactory meetupsFactory(FakeMeetupsFactory fakeMeetupsFactory) {
    return fakeMeetupsFactory;
  }
}
