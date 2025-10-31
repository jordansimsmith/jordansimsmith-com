package com.jordansimsmith.immersiontracker;

import com.jordansimsmith.dynamodb.DynamoDbTestModule;
import com.jordansimsmith.json.ObjectMapperModule;
import com.jordansimsmith.secrets.FakeSecrets;
import com.jordansimsmith.secrets.SecretsTestModule;
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
      SecretsTestModule.class,
      ObjectMapperModule.class,
      DynamoDbTestModule.class,
      ImmersionTrackerTestModule.class
    })
public interface ImmersionTrackerTestFactory extends ImmersionTrackerFactory {
  FakeClock fakeClock();

  FakeSecrets fakeSecrets();

  FakeTvdbClient fakeTvdbClient();

  FakeYoutubeClient fakeYoutubeClient();

  FakeSpotifyClient fakeSpotifyClient();

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
