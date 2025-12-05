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
  public DynamoDbTable<EventCalendarItem> eventCalendarTable(
      DynamoDbEnhancedClient dynamoDbEnhancedClient) {
    var schema = TableSchema.fromBean(EventCalendarItem.class);
    return dynamoDbEnhancedClient.table("event_calendar", schema);
  }

  @Provides
  @Singleton
  public FakeGoMediaEventClient fakeGoMediaEventClient() {
    return new FakeGoMediaEventClient();
  }

  @Provides
  @Singleton
  public GoMediaEventClient goMediaEventClient(FakeGoMediaEventClient fakeGoMediaEventClient) {
    return fakeGoMediaEventClient;
  }

  @Provides
  @Singleton
  public FakeMeetupClient fakeMeetupClient() {
    return new FakeMeetupClient();
  }

  @Provides
  @Singleton
  public MeetupClient meetupClient(FakeMeetupClient fakeMeetupClient) {
    return fakeMeetupClient;
  }

  @Provides
  @Singleton
  public FakeMeetupsFactory fakeMeetupsFactory() {
    return new FakeMeetupsFactory();
  }

  @Provides
  @Singleton
  public MeetupsFactory meetupsFactory(FakeMeetupsFactory fakeMeetupsFactory) {
    return fakeMeetupsFactory;
  }

  @Provides
  @Singleton
  public FakeLeinsterRugbyClient fakeLeinsterRugbyClient() {
    return new FakeLeinsterRugbyClient();
  }

  @Provides
  @Singleton
  public LeinsterRugbyClient leinsterRugbyClient(FakeLeinsterRugbyClient fakeLeinsterRugbyClient) {
    return fakeLeinsterRugbyClient;
  }
}
