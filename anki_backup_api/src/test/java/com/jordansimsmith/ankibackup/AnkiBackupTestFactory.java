package com.jordansimsmith.ankibackup;

import com.jordansimsmith.dynamodb.DynamoDbTestModule;
import com.jordansimsmith.http.RequestContextModule;
import com.jordansimsmith.json.ObjectMapperModule;
import com.jordansimsmith.s3.S3TestModule;
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
      S3TestModule.class,
      RequestContextModule.class,
      AnkiBackupTestModule.class
    })
public interface AnkiBackupTestFactory extends AnkiBackupFactory {
  FakeClock fakeClock();

  FakeSecrets fakeSecrets();

  DynamoDbClient dynamoDbClient();

  @Component.Factory
  interface Factory {
    AnkiBackupTestFactory create(
        @BindsInstance @Named("dynamoDbEndpoint") URI dynamoDbEndpoint,
        @BindsInstance @Named("s3Endpoint") URI s3Endpoint);
  }

  static AnkiBackupTestFactory create(URI dynamoDbEndpoint, URI s3Endpoint) {
    return DaggerAnkiBackupTestFactory.factory().create(dynamoDbEndpoint, s3Endpoint);
  }
}
