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
  DynamoDbTable<FootballCalendarItem> footballCalendarTable(
      DynamoDbEnhancedClient dynamoDbEnhancedClient) {
    var schema = TableSchema.fromBean(FootballCalendarItem.class);
    return dynamoDbEnhancedClient.table("football_calendar", schema);
  }

  @Provides
  @Singleton
  FakeNrfClient fakeNrfClient() {
    return new FakeNrfClient();
  }

  @Provides
  @Singleton
  NrfClient nrfClient(FakeNrfClient fakeNrfClient) {
    return fakeNrfClient;
  }

  @Provides
  @Singleton
  FakeFootballFixClient fakeFootballFixClient() {
    return new FakeFootballFixClient();
  }

  @Provides
  @Singleton
  FootballFixClient footballFixClient(FakeFootballFixClient fakeFootballFixClient) {
    return fakeFootballFixClient;
  }

  @Provides
  @Singleton
  FakeSubfootballClient fakeSubfootballClient() {
    return new FakeSubfootballClient();
  }

  @Provides
  @Singleton
  SubfootballClient subfootballClient(FakeSubfootballClient fakeSubfootballClient) {
    return fakeSubfootballClient;
  }

  @Provides
  @Singleton
  FakeTeamsFactory fakeTeamsFactory() {
    return new FakeTeamsFactory();
  }

  @Provides
  @Singleton
  TeamsFactory teamsFactory(FakeTeamsFactory fakeTeamsFactory) {
    return fakeTeamsFactory;
  }
}
