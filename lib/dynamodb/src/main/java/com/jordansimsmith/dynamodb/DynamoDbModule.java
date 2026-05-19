package com.jordansimsmith.dynamodb;

import dagger.Module;
import dagger.Provides;
import javax.inject.Singleton;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.ListTablesRequest;

@Module
public class DynamoDbModule {
  @Provides
  @Singleton
  DynamoDbClient dynamoDbClient() {
    var client = DynamoDbClient.builder().region(Region.of(System.getenv("AWS_REGION"))).build();
    // prime the snapshot to optimise cold start times
    client.listTables(ListTablesRequest.builder().limit(1).build());
    return client;
  }

  @Provides
  @Singleton
  DynamoDbEnhancedClient dynamoDbEnhancedClient(DynamoDbClient dynamoDbClient) {
    return DynamoDbEnhancedClient.builder().dynamoDbClient(dynamoDbClient).build();
  }
}
