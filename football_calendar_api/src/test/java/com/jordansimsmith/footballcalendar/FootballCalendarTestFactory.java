package com.jordansimsmith.footballcalendar;

import com.jordansimsmith.dynamodb.DynamoDbTestModule;
import com.jordansimsmith.json.ObjectMapperModule;
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
      FootballCalendarTestModule.class,
      ObjectMapperModule.class
    })
public interface FootballCalendarTestFactory extends FootballCalendarFactory {
  FakeClock fakeClock();

  FakeCometClient fakeCometClient();

  FakeFootballFixClient fakeFootballFixClient();

  FakeSubfootballClient fakeSubfootballClient();

  FakeTeamsFactory fakeTeamsFactory();

  DynamoDbClient dynamoDbClient();

  @Component.Factory
  interface Factory {
    FootballCalendarTestFactory create(
        @BindsInstance @Named("dynamoDbEndpoint") URI dynamoDbEndpoint);
  }

  static FootballCalendarTestFactory create(URI dynamoDbEndpoint) {
    return DaggerFootballCalendarTestFactory.factory().create(dynamoDbEndpoint);
  }
}
