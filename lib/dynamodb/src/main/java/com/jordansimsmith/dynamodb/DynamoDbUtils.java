package com.jordansimsmith.dynamodb;

import java.util.stream.Collectors;
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

  public static void reset(DynamoDbClient dynamoDbClient) {
    var tables = dynamoDbClient.listTables().tableNames();

    for (var tableName : tables) {
      var describeTableResponse = dynamoDbClient.describeTable(r -> r.tableName(tableName));
      var keySchema = describeTableResponse.table().keySchema();

      var items = dynamoDbClient.scan(r -> r.tableName(tableName)).items();

      for (var item : items) {
        var key =
            keySchema.stream()
                .collect(
                    Collectors.toMap(k -> k.attributeName(), k -> item.get(k.attributeName())));

        dynamoDbClient.deleteItem(r -> r.tableName(tableName).key(key));
      }
    }
  }
}
