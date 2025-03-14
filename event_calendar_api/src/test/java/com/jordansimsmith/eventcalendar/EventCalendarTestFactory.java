package com.jordansimsmith.eventcalendar;

import com.jordansimsmith.dynamodb.DynamoDbTestModule;
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
    modules = {ClockTestModule.class, DynamoDbTestModule.class, EventCalendarTestModule.class})
public interface EventCalendarTestFactory extends EventCalendarFactory {
  FakeClock fakeClock();

  DynamoDbClient dynamoDbClient();

  FakeGoMediaEventClient fakeGoMediaEventClient();

  @Component.Factory
  interface Factory {
    EventCalendarTestFactory create(@BindsInstance @Named("dynamoDbEndpoint") URI dynamoDbEndpoint);
  }

  static EventCalendarTestFactory create(URI dynamoDbEndpoint) {
    return DaggerEventCalendarTestFactory.factory().create(dynamoDbEndpoint);
  }
}
