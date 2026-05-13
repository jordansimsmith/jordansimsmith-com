package com.jordansimsmith.booktracker;

import com.jordansimsmith.auth.AuthModule;
import com.jordansimsmith.dynamodb.DynamoDbTestModule;
import com.jordansimsmith.http.RequestContextModule;
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
      ObjectMapperModule.class,
      SecretsTestModule.class,
      ClockTestModule.class,
      DynamoDbTestModule.class,
      RequestContextModule.class,
      AuthModule.class,
      BookTrackerTestModule.class
    })
public interface BookTrackerTestFactory extends BookTrackerFactory {
  FakeSecrets fakeSecrets();

  FakeClock fakeClock();

  DynamoDbClient dynamoDbClient();

  @Component.Factory
  interface Factory {
    BookTrackerTestFactory create(@BindsInstance @Named("dynamoDbEndpoint") URI dynamoDbEndpoint);
  }

  static BookTrackerTestFactory create(URI dynamoDbEndpoint) {
    return DaggerBookTrackerTestFactory.factory().create(dynamoDbEndpoint);
  }
}
