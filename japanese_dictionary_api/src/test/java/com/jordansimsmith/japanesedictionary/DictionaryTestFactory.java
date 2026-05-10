package com.jordansimsmith.japanesedictionary;

import com.jordansimsmith.dynamodb.DynamoDbTestModule;
import com.jordansimsmith.http.RequestContextModule;
import com.jordansimsmith.json.ObjectMapperModule;
import com.jordansimsmith.secrets.FakeSecrets;
import com.jordansimsmith.secrets.SecretsTestModule;
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
      DynamoDbTestModule.class,
      RequestContextModule.class,
      DictionaryModule.class
    })
public interface DictionaryTestFactory extends DictionaryFactory {
  FakeSecrets fakeSecrets();

  @Component.Factory
  interface Factory {
    DictionaryTestFactory create(@BindsInstance @Named("dynamoDbEndpoint") URI dynamoDbEndpoint);
  }

  static DictionaryTestFactory create(URI dynamoDbEndpoint) {
    return DaggerDictionaryTestFactory.factory().create(dynamoDbEndpoint);
  }
}
