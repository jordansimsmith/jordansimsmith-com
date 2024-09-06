package com.jordansimsmith.immersiontracker;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jordansimsmith.dynamodb.DynamoDbModule;
import com.jordansimsmith.json.ObjectMapperModule;
import com.jordansimsmith.time.Clock;
import com.jordansimsmith.time.ClockModule;
import dagger.Component;
import javax.inject.Singleton;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;

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

  DynamoDbTable<ImmersionTrackerItem> immersionTrackerTable();

  static ImmersionTrackerFactory create() {
    return DaggerImmersionTrackerFactory.create();
  }
}
