package com.jordansimsmith.auctiontracker;

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
      AuctionTrackerTestModule.class,
    })
public interface AuctionTrackerTestFactory extends AuctionTrackerFactory {
  FakeClock fakeClock();

  DynamoDbClient dynamoDbClient();

  FakeNotificationPublisher fakeNotificationPublisher();

  FakeSearchFactory fakeSearchFactory();

  FakeTradeMeClient fakeTradeMeClient();

  @Component.Factory
  interface Factory {
    AuctionTrackerTestFactory create(
        @BindsInstance @Named("dynamoDbEndpoint") URI dynamoDbEndpoint);
  }

  static AuctionTrackerTestFactory create(URI dynamoDbEndpoint) {
    return DaggerAuctionTrackerTestFactory.factory().create(dynamoDbEndpoint);
  }
}
