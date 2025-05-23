package com.jordansimsmith.footballcalendar;

import dagger.Module;
import dagger.Provides;
import javax.inject.Singleton;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;

@Module
public class FootballCalendarTestModule {

  @Provides
  @Singleton
  public DynamoDbTable<FootballCalendarItem> footballCalendarTable(
      DynamoDbEnhancedClient dynamoDbEnhancedClient) {
    var schema = TableSchema.fromBean(FootballCalendarItem.class);
    return dynamoDbEnhancedClient.table("football_calendar", schema);
  }

  @Provides
  @Singleton
  public FakeCometClient fakeCometClient() {
    return new FakeCometClient();
  }

  @Provides
  @Singleton
  public CometClient cometClient(FakeCometClient fakeCometClient) {
    return fakeCometClient;
  }
}
