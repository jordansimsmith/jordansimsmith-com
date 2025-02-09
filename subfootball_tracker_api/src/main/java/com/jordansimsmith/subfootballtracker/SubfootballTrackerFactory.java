package com.jordansimsmith.subfootballtracker;

import com.jordansimsmith.dynamodb.DynamoDbModule;
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
      NotificationModule.class,
      DynamoDbModule.class,
      SubfootballTrackerModule.class
    })
public interface SubfootballTrackerFactory {
  Clock clock();

  NotificationPublisher notificationPublisher();

  DynamoDbTable<SubfootballTrackerItem> subfootballTrackerTable();

  SubfootballClient subfootballClient();

  static SubfootballTrackerFactory create() {
    return DaggerSubfootballTrackerFactory.create();
  }
}
