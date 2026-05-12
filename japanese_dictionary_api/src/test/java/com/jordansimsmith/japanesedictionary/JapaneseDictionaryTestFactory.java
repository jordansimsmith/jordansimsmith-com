package com.jordansimsmith.japanesedictionary;

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

@Singleton
@Component(
    modules = {
      ObjectMapperModule.class,
      SecretsTestModule.class,
      ClockTestModule.class,
      DynamoDbTestModule.class,
      RequestContextModule.class,
      JapaneseDictionaryModule.class
    })
public interface JapaneseDictionaryTestFactory extends JapaneseDictionaryFactory {
  FakeSecrets fakeSecrets();

  FakeClock fakeClock();

  @Component.Factory
  interface Factory {
    JapaneseDictionaryTestFactory create(
        @BindsInstance @Named("dynamoDbEndpoint") URI dynamoDbEndpoint);
  }

  static JapaneseDictionaryTestFactory create(URI dynamoDbEndpoint) {
    return DaggerJapaneseDictionaryTestFactory.factory().create(dynamoDbEndpoint);
  }
}
