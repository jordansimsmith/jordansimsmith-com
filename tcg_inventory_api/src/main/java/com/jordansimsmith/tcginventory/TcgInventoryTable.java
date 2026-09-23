package com.jordansimsmith.tcginventory;

import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;

public class TcgInventoryTable {
  public static final String TABLE_NAME = "tcg_inventory";
  public static final String GSI1_NAME = "gsi1";
  public static final String GSI2_NAME = "gsi2";
  public static final String GSI3_NAME = "gsi3";

  public static <T> DynamoDbTable<T> table(DynamoDbEnhancedClient client, Class<T> itemClass) {
    return client.table(TABLE_NAME, TableSchema.fromBean(itemClass));
  }
}
