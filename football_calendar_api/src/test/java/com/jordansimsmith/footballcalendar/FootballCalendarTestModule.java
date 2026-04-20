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
  public FakeNrfClient fakeNrfClient() {
    return new FakeNrfClient();
  }

  @Provides
  @Singleton
  public NrfClient nrfClient(FakeNrfClient fakeNrfClient) {
    return fakeNrfClient;
  }

  @Provides
  @Singleton
  public FakeFootballFixClient fakeFootballFixClient() {
    return new FakeFootballFixClient();
  }

  @Provides
  @Singleton
  public FootballFixClient footballFixClient(FakeFootballFixClient fakeFootballFixClient) {
    return fakeFootballFixClient;
  }

  @Provides
  @Singleton
  public FakeSubfootballClient fakeSubfootballClient() {
    return new FakeSubfootballClient();
  }

  @Provides
  @Singleton
  public SubfootballClient subfootballClient(FakeSubfootballClient fakeSubfootballClient) {
    return fakeSubfootballClient;
  }

  @Provides
  @Singleton
  public FakeTeamsFactory fakeTeamsFactory() {
    return new FakeTeamsFactory();
  }

  @Provides
  @Singleton
  public TeamsFactory teamsFactory(FakeTeamsFactory fakeTeamsFactory) {
    return fakeTeamsFactory;
  }
}
