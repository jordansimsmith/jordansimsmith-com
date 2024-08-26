package com.jordansimsmith.immersiontracker;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jordansimsmith.dynamodb.DynamoDbModule;
import com.jordansimsmith.json.ObjectMapperModule;
import com.jordansimsmith.time.Clock;
import com.jordansimsmith.time.ClockModule;
import dagger.Component;
import javax.inject.Singleton;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

@Singleton
@Component(
    modules = {
      ClockModule.class,
      ObjectMapperModule.class,
      DynamoDbModule.class,
      ImmersionTrackerModule.class
    })
public interface ImmersionTrackerFactory {
  Clock clock();

  ObjectMapper objectMapper();

  DynamoDbClient dynamoDbClient();

  DynamoDbEnhancedClient dynamoDbEnhancedClient();

  DynamoDbTable<ImmersionTrackerItem> immersionTrackerTable();

  static ImmersionTrackerFactory create() {
    return DaggerImmersionTrackerFactory.create();
  }
}
