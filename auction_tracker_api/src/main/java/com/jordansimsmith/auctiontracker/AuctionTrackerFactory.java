package com.jordansimsmith.auctiontracker;

import com.jordansimsmith.dynamodb.DynamoDbModule;
import com.jordansimsmith.json.ObjectMapperModule;
import com.jordansimsmith.notifications.NotificationModule;
import com.jordansimsmith.notifications.NotificationPublisher;
import com.jordansimsmith.prompts.PromptsModule;
import com.jordansimsmith.secrets.SecretsModule;
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
      ObjectMapperModule.class,
      PromptsModule.class,
      SecretsModule.class,
      AuctionTrackerModule.class
    })
public interface AuctionTrackerFactory {
  Clock clock();

  DynamoDbTable<AuctionTrackerItem> auctionTrackerTable();

  NotificationPublisher notificationPublisher();

  SearchFactory searchFactory();

  TradeMeClient tradeMeClient();

  ListingJudge listingJudge();

  static AuctionTrackerFactory create() {
    return DaggerAuctionTrackerFactory.create();
  }
}
