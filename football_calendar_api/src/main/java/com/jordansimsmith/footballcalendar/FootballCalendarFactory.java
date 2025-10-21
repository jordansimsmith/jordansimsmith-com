package com.jordansimsmith.footballcalendar;

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
      DynamoDbModule.class,
      ObjectMapperModule.class,
      FootballCalendarModule.class
    })
public interface FootballCalendarFactory {
  Clock clock();

  DynamoDbTable<FootballCalendarItem> footballCalendarTable();

  CometClient cometClient();

  TeamsFactory teamsFactory();

  static FootballCalendarFactory create() {
    return DaggerFootballCalendarFactory.create();
  }
}
