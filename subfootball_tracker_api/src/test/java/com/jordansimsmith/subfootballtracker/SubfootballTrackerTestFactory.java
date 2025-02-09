package com.jordansimsmith.subfootballtracker;

import com.jordansimsmith.dynamodb.DynamoDbTestModule;
import com.jordansimsmith.notifications.FakeNotificationPublisher;
import com.jordansimsmith.notifications.NotificationTestModule;
import com.jordansimsmith.time.ClockTestModule;
import com.jordansimsmith.time.FakeClock;
import dagger.BindsInstance;
import dagger.Component;
import java.net.URI;
import javax.inject.Named;
import javax.inject.Singleton;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

@Singleton
@Component(
    modules = {
      ClockTestModule.class,
      DynamoDbTestModule.class,
      NotificationTestModule.class,
      SubfootballTrackerTestModule.class
    })
public interface SubfootballTrackerTestFactory extends SubfootballTrackerFactory {
  FakeClock fakeClock();

  DynamoDbClient dynamoDbClient();

  FakeNotificationPublisher fakeNotificationPublisher();

  FakeSubfootballClient fakeSubfootballClient();

  @Component.Factory
  interface Factory {
    SubfootballTrackerTestFactory create(
        @BindsInstance @Named("dynamoDbEndpoint") URI dynamoDbEndpoint);
  }

  static SubfootballTrackerTestFactory create(URI dynamoDbEndpoint) {
    return DaggerSubfootballTrackerTestFactory.factory().create(dynamoDbEndpoint);
  }
}
