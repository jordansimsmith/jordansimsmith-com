package com.jordansimsmith.dynamodb;

import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.waiters.DynamoDbWaiter;

public class DynamoDbUtils {

  public static void createTable(DynamoDbClient dynamoDbClient, DynamoDbTable<?> table) {
    table.createTable();

    try (var waiter = DynamoDbWaiter.builder().client(dynamoDbClient).build()) {
      var res = waiter.waitUntilTableExists(b -> b.tableName(table.tableName()).build()).matched();
      res.response().orElseThrow();
    }
  }
}
