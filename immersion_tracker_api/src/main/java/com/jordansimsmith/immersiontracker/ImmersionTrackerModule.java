package com.jordansimsmith.immersiontracker;

import com.fasterxml.jackson.databind.ObjectMapper;
import dagger.Module;
import dagger.Provides;
import java.net.URI;
import javax.annotation.Nullable;
import javax.inject.Named;
import javax.inject.Singleton;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

@Module
public class ImmersionTrackerModule {
  @Provides
  public ObjectMapper objectMapper() {
    return new ObjectMapper();
  }

  @Provides
  @Singleton
  public DynamoDbClient dynamoDbClient(@Named("dynamoDbEndpoint") @Nullable URI dynamoDbEndpoint) {
    var builder = DynamoDbClient.builder();
    if (dynamoDbEndpoint != null) {
      builder.endpointOverride(dynamoDbEndpoint);
    }

    return builder.build();
  }

  @Provides
  @Singleton
  public DynamoDbEnhancedClient dynamoDbEnhancedClient(DynamoDbClient dynamoDbClient) {
    return DynamoDbEnhancedClient.builder().dynamoDbClient(dynamoDbClient).build();
  }

  @Provides
  @Singleton
  public DynamoDbTable<ImmersionTrackerItem> immersionTrackerTable(
      DynamoDbEnhancedClient dynamoDbEnhancedClient) {
    var schema = TableSchema.fromBean(ImmersionTrackerItem.class);
    return dynamoDbEnhancedClient.table("immersion_tracker", schema);
  }
}
