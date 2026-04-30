package com.jordansimsmith.footballcalendar;

import com.jordansimsmith.dynamodb.DynamoDbModule;
import com.jordansimsmith.json.ObjectMapperModule;
import com.jordansimsmith.notifications.NotificationModule;
import com.jordansimsmith.notifications.NotificationPublisher;
import com.jordansimsmith.time.Clock;
import com.jordansimsmith.time.ClockModule;
import dagger.Component;
import javax.inject.Singleton;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;

@Singleton
@Component(
    modules = {
      ClockModule.class,
      DynamoDbModule.class,
      NotificationModule.class,
      ObjectMapperModule.class,
      FootballCalendarModule.class
    })
public interface FootballCalendarFactory {
  Clock clock();

  NotificationPublisher notificationPublisher();

  DynamoDbTable<FootballCalendarItem> footballCalendarTable();

  NrfClient nrfClient();

  FootballFixClient footballFixClient();

  SubfootballClient subfootballClient();

  TeamsFactory teamsFactory();

  static FootballCalendarFactory create() {
    return DaggerFootballCalendarFactory.create();
  }
}
