package com.jordansimsmith.immersiontracker;

import dagger.Module;
import dagger.Provides;
import java.net.URI;
import javax.annotation.Nullable;
import javax.inject.Named;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

@Module
public class DynamoDbModule {

  @Provides
  public DynamoDbClient dynamoDbClient(@Named("dynamoDbEndpoint") @Nullable URI dynamoDbEndpoint) {
    var builder = DynamoDbClient.builder();
    if (dynamoDbEndpoint != null) {
      builder.endpointOverride(dynamoDbEndpoint);
    }

    return builder.build();
  }

  @Provides
  public DynamoDbEnhancedClient dynamoDbEnhancedClient(DynamoDbClient dynamoDbClient) {
    return DynamoDbEnhancedClient.builder().dynamoDbClient(dynamoDbClient).build();
  }
}
