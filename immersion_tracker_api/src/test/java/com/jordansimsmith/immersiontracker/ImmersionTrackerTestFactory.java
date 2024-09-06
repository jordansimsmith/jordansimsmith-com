package com.jordansimsmith.immersiontracker;

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
      ObjectMapperModule.class,
      DynamoDbTestModule.class,
      ImmersionTrackerModule.class
    })
public interface ImmersionTrackerTestFactory extends ImmersionTrackerFactory {
  FakeClock fakeClock();

  DynamoDbClient dynamoDbClient();

  @Component.Factory
  interface Factory {
    ImmersionTrackerTestFactory create(
        @BindsInstance @Named("dynamoDbEndpoint") URI dynamoDbEndpoint);
  }

  static ImmersionTrackerTestFactory create(URI dynamoDbEndpoint) {
    return DaggerImmersionTrackerTestFactory.factory().create(dynamoDbEndpoint);
  }
}
