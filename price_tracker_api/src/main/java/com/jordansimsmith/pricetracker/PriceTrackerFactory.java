package com.jordansimsmith.pricetracker;

import com.jordansimsmith.dynamodb.DynamoDbModule;
import com.jordansimsmith.lib.notifications.NotificationModule;
import com.jordansimsmith.lib.notifications.NotificationPublisher;
import com.jordansimsmith.time.Clock;
import com.jordansimsmith.time.ClockModule;
import dagger.Component;
import javax.inject.Singleton;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;

@Singleton
@Component(
    modules = {
      ClockModule.class,
      NotificationModule.class,
      DynamoDbModule.class,
      PriceTrackerModule.class
    })
public interface PriceTrackerFactory {
  Clock clock();

  DynamoDbTable<PriceTrackerItem> priceTrackerTable();

  NotificationPublisher notificationPublisher();

  ChemistWarehouseClient chemistWarehouseClient();

  ProductsFactory productsFactory();

  static PriceTrackerFactory create() {
    return DaggerPriceTrackerFactory.create();
  }
}