package com.jordansimsmith.tcginventory;

import com.jordansimsmith.dynamodb.DynamoDbTestModule;
import com.jordansimsmith.http.RequestContextModule;
import com.jordansimsmith.json.ObjectMapperModule;
import com.jordansimsmith.queue.FakeQueueClient;
import com.jordansimsmith.s3.S3TestModule;
import com.jordansimsmith.secrets.FakeSecrets;
import com.jordansimsmith.secrets.SecretsTestModule;
import com.jordansimsmith.tcginventory.fetchtcg.client.FakeFetchTcgService;
import com.jordansimsmith.tcginventory.imports.server.ImportMessage;
import com.jordansimsmith.tcginventory.inventory.client.FakeInventoryService;
import com.jordansimsmith.tcginventory.orders.server.FulfillmentMessage;
import com.jordansimsmith.tcginventory.photos.client.FakePhotosService;
import com.jordansimsmith.tcginventory.pricing.server.RepricingMessage;
import com.jordansimsmith.tcginventory.publish.server.PublishMessage;
import com.jordansimsmith.time.ClockTestModule;
import com.jordansimsmith.time.FakeClock;
import com.jordansimsmith.ulid.FakeUlidGenerator;
import com.jordansimsmith.ulid.UlidTestModule;
import dagger.BindsInstance;
import dagger.Component;
import java.net.URI;
import javax.inject.Named;
import javax.inject.Singleton;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;

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
  URI UNUSED_S3_ENDPOINT = URI.create("http://localhost:1");

  FakeClock fakeClock();

  FakeSecrets fakeSecrets();

  FakeUlidGenerator fakeUlidGenerator();

  FakeQueueClient<RepricingMessage> fakePricingQueue();

  FakeQueueClient<PublishMessage> fakePublishQueue();

  FakeQueueClient<FulfillmentMessage> fakeOrdersQueue();

  FakeQueueClient<ImportMessage> fakeImportsQueue();

  FakeFetchTcgService fakeFetchTcgService();

  FakePhotosService fakePhotosService();

  DynamoDbTable<TcgInventoryTableDefinition> tableDefinition();

  FakeInventoryService fakeInventoryService();

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
