package com.jordansimsmith.pricetracker;

import com.jordansimsmith.dynamodb.DynamoDbTestModule;
import com.jordansimsmith.notifications.FakeNotificationPublisher;
import com.jordansimsmith.notifications.NotificationTestModule;
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
      NotificationTestModule.class,
      PriceTrackerTestModule.class,
    })
public interface PriceTrackerTestFactory extends PriceTrackerFactory {
  FakeClock fakeClock();

  DynamoDbClient dynamoDbClient();

  FakeNotificationPublisher fakeNotificationPublisher();

  FakePriceClient fakePriceClient();

  FakeProductsFactory fakeProductsFactory();

  @Component.Factory
  interface Factory {
    PriceTrackerTestFactory create(@BindsInstance @Named("dynamoDbEndpoint") URI dynamoDbEndpoint);
  }

  static PriceTrackerTestFactory create(URI dynamoDbEndpoint) {
    return DaggerPriceTrackerTestFactory.factory().create(dynamoDbEndpoint);
  }
}
