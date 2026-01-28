package com.jordansimsmith.packinglist;

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
      PackingListTestModule.class
    })
public interface PackingListTestFactory extends PackingListFactory {
  FakeSecrets fakeSecrets();

  FakeClock fakeClock();

  FakeTemplatesFactory fakeTemplatesFactory();

  DynamoDbClient dynamoDbClient();

  @Component.Factory
  interface Factory {
    PackingListTestFactory create(@BindsInstance @Named("dynamoDbEndpoint") URI dynamoDbEndpoint);
  }

  static PackingListTestFactory create(URI dynamoDbEndpoint) {
    return DaggerPackingListTestFactory.factory().create(dynamoDbEndpoint);
  }
}
