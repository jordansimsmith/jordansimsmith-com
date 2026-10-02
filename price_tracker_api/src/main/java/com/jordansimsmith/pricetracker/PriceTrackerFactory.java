package com.jordansimsmith.pricetracker;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jordansimsmith.dynamodb.DynamoDbModule;
import com.jordansimsmith.json.ObjectMapperModule;
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
      NotificationModule.class,
      DynamoDbModule.class,
      ObjectMapperModule.class,
      PriceTrackerModule.class
    })
public interface PriceTrackerFactory {
  ObjectMapper objectMapper();

  Clock clock();

  DynamoDbTable<PriceTrackerItem> priceTrackerTable();

  DynamoDbTable<DigestCheckpointItem> digestCheckpointTable();

  NotificationPublisher notificationPublisher();

  PriceClient priceClient();

  ProductsFactory productsFactory();

  static PriceTrackerFactory create() {
    return DaggerPriceTrackerFactory.create();
  }
}
