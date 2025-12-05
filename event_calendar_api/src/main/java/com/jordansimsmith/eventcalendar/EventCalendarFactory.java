package com.jordansimsmith.eventcalendar;

import com.jordansimsmith.dynamodb.DynamoDbModule;
import com.jordansimsmith.time.Clock;
import com.jordansimsmith.time.ClockModule;
import dagger.Component;
import javax.inject.Singleton;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;

@Singleton
@Component(modules = {ClockModule.class, DynamoDbModule.class, EventCalendarModule.class})
public interface EventCalendarFactory {
  Clock clock();

  DynamoDbTable<EventCalendarItem> eventCalendarTable();

  GoMediaEventClient goMediaEventClient();

  MeetupClient meetupClient();

  MeetupsFactory meetupsFactory();

  LeinsterRugbyClient leinsterRugbyClient();

  static EventCalendarFactory create() {
    return DaggerEventCalendarFactory.create();
  }
}
