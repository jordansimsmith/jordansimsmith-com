package com.jordansimsmith.auctiontracker;

import com.jordansimsmith.dynamodb.DynamoDbModule;
import com.jordansimsmith.notifications.NotificationModule;
import com.jordansimsmith.notifications.NotificationPublisher;
import com.jordansimsmith.time.Clock;
import com.jordansimsmith.time.ClockModule;
import dagger.Component;
import javax.inject.Singleton;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;

@Singleton
@Component(
    modules = {
      ClockModule.class,
      DynamoDbModule.class,
      NotificationModule.class,
      AuctionTrackerModule.class
    })
public interface AuctionTrackerFactory {
  Clock clock();

  DynamoDbTable<AuctionTrackerItem> auctionTrackerTable();

  NotificationPublisher notificationPublisher();

  SearchFactory searchFactory();

  TradeMeClient tradeMeClient();

  static AuctionTrackerFactory create() {
    return DaggerAuctionTrackerFactory.create();
  }
}
