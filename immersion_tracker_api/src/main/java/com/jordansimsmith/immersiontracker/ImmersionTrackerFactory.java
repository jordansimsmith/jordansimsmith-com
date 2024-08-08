package com.jordansimsmith.immersiontracker;

import dagger.BindsInstance;
import dagger.Component;
import java.net.URI;
import javax.annotation.Nullable;
import javax.inject.Named;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

@Component(modules = {DynamoDbModule.class})
public interface ImmersionTrackerFactory {

  DynamoDbClient dynamoDbClient();

  DynamoDbEnhancedClient dynamoDbEnhancedClient();

  @Component.Builder
  interface Builder {
    @BindsInstance
    Builder dynamoDbEndpoint(@Named("dynamoDbEndpoint") @Nullable URI dynamoDbEndpoint);

    ImmersionTrackerFactory build();
  }

  static ImmersionTrackerFactory create() {
    return DaggerImmersionTrackerFactory.builder().build();
  }

  static ImmersionTrackerFactory.Builder builder() {
    return DaggerImmersionTrackerFactory.builder();
  }
}
