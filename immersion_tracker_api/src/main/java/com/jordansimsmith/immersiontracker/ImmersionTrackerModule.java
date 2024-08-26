package com.jordansimsmith.immersiontracker;

import dagger.Module;
import dagger.Provides;
import javax.inject.Singleton;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;

@Module
public class ImmersionTrackerModule {
  @Provides
  @Singleton
  public DynamoDbTable<ImmersionTrackerItem> immersionTrackerTable(
      DynamoDbEnhancedClient dynamoDbEnhancedClient) {
    var schema = TableSchema.fromBean(ImmersionTrackerItem.class);
    return dynamoDbEnhancedClient.table("immersion_tracker", schema);
  }
}
