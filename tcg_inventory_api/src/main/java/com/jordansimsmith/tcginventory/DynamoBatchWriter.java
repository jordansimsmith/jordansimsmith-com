package com.jordansimsmith.tcginventory;

import java.util.List;
import java.util.Map;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.BatchWriteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.WriteRequest;

public class DynamoBatchWriter {
  private static final int MAX_BATCH_SIZE = 25;
  private static final int MAX_RETRIES = 8;

  public static void write(DynamoDbClient client, String tableName, List<WriteRequest> writes) {
    for (int start = 0; start < writes.size(); start += MAX_BATCH_SIZE) {
      var end = Math.min(start + MAX_BATCH_SIZE, writes.size());
      var pending = Map.of(tableName, List.copyOf(writes.subList(start, end)));
      for (int attempt = 0; !pending.isEmpty(); attempt++) {
        pending =
            client
                .batchWriteItem(BatchWriteItemRequest.builder().requestItems(pending).build())
                .unprocessedItems();
        if (!pending.isEmpty()) {
          if (attempt >= MAX_RETRIES) {
            throw new IllegalStateException("DynamoDB batch write left unprocessed items");
          }
          try {
            Thread.sleep(Math.min(50L << attempt, 2000L));
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("interrupted while retrying DynamoDB batch write", e);
          }
        }
      }
    }
  }
}
