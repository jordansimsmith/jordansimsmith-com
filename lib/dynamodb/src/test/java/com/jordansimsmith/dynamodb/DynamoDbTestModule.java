package com.jordansimsmith.dynamodb;

import dagger.Module;
import dagger.Provides;
import java.net.URI;
import javax.inject.Named;
import javax.inject.Singleton;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

@Module
public class DynamoDbTestModule {
  @Provides
  @Singleton
  DynamoDbClient dynamoDbClient(@Named("dynamoDbEndpoint") URI dynamoDbEndpoint) {
    return DynamoDbClient.builder().endpointOverride(dynamoDbEndpoint).build();
  }

  @Provides
  @Singleton
  DynamoDbEnhancedClient dynamoDbEnhancedClient(DynamoDbClient dynamoDbClient) {
    return DynamoDbEnhancedClient.builder().dynamoDbClient(dynamoDbClient).build();
  }
}
