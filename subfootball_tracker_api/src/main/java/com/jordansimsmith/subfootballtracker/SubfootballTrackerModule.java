package com.jordansimsmith.subfootballtracker;

import dagger.Module;
import dagger.Provides;
import java.net.URI;
import javax.inject.Singleton;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;

@Module
public class SubfootballTrackerModule {
  @Provides
  @Singleton
  public DynamoDbTable<SubfootballTrackerItem> subfootballTrackerTable(
      DynamoDbEnhancedClient dynamoDbEnhancedClient) {
    var schema = TableSchema.fromBean(SubfootballTrackerItem.class);
    return dynamoDbEnhancedClient.table("subfootball_tracker", schema);
  }

  @Provides
  @Singleton
  public SubfootballClient subfootballClient() {
    var baseUrl = System.getenv("SUBFOOTBALL_TRACKER_SUBFOOTBALL_BASE_URL");
    if (baseUrl == null || baseUrl.isBlank()) {
      baseUrl = "https://subfootball.com";
    }
    return new JsoupSubfootballClient(URI.create(baseUrl));
  }
}
