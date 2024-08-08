package com.jordansimsmith.immersiontracker;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.google.common.annotations.VisibleForTesting;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

public class GetProgressHandler implements RequestHandler<Object, String> {
  private final DynamoDbClient dynamoDbClient;
  private final DynamoDbEnhancedClient dynamoDbEnhancedClient;

  public GetProgressHandler() {
    this(ImmersionTrackerFactory.create());
  }

  @VisibleForTesting
  GetProgressHandler(ImmersionTrackerFactory factory) {
    this.dynamoDbEnhancedClient = factory.dynamoDbEnhancedClient();
    this.dynamoDbClient = factory.dynamoDbClient();
  }

  @Override
  public String handleRequest(Object s, Context context) {
    return dynamoDbClient.listTables().toString();
  }
}
