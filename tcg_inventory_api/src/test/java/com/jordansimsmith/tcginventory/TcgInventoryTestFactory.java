package com.jordansimsmith.tcginventory;

import com.jordansimsmith.dynamodb.DynamoDbTestModule;
import com.jordansimsmith.http.RequestContextModule;
import com.jordansimsmith.json.ObjectMapperModule;
import com.jordansimsmith.queue.FakeQueueClient;
import com.jordansimsmith.s3.S3TestModule;
import com.jordansimsmith.secrets.FakeSecrets;
import com.jordansimsmith.secrets.SecretsTestModule;
import com.jordansimsmith.time.ClockTestModule;
import com.jordansimsmith.time.FakeClock;
import com.jordansimsmith.ulid.FakeUlidGenerator;
import com.jordansimsmith.ulid.UlidTestModule;
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
      ClockTestModule.class,
      DynamoDbTestModule.class,
      SecretsTestModule.class,
      RequestContextModule.class,
      UlidTestModule.class,
      S3TestModule.class,
      TcgInventoryTestModule.class
    })
public interface TcgInventoryTestFactory extends TcgInventoryFactory {
  FakeClock fakeClock();

  FakeSecrets fakeSecrets();

  FakeUlidGenerator fakeUlidGenerator();

  FakeQueueClient<JobMessage> fakeJobsQueue();

  FakeFetchTcgClient fakeFetchTcgClient();

  DynamoDbClient dynamoDbClient();

  @Component.Factory
  interface Factory {
    TcgInventoryTestFactory create(
        @BindsInstance @Named("dynamoDbEndpoint") URI dynamoDbEndpoint,
        @BindsInstance @Named("s3Endpoint") URI s3Endpoint);
  }

  static TcgInventoryTestFactory create(URI dynamoDbEndpoint, URI s3Endpoint) {
    return DaggerTcgInventoryTestFactory.factory().create(dynamoDbEndpoint, s3Endpoint);
  }
}
