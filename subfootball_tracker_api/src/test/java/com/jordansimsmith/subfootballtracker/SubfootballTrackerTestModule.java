package com.jordansimsmith.subfootballtracker;

import dagger.Module;
import dagger.Provides;
import javax.inject.Singleton;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;

@Module
public class SubfootballTrackerTestModule {
  @Provides
  @Singleton
  public DynamoDbTable<SubfootballTrackerItem> subfootballTrackerTable(
      DynamoDbEnhancedClient dynamoDbEnhancedClient) {
    var schema = TableSchema.fromBean(SubfootballTrackerItem.class);
    return dynamoDbEnhancedClient.table("subfootball_tracker", schema);
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
}
