package com.jordansimsmith.tcginventory;

import com.jordansimsmith.time.Clock;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;

class AppraiseJobProcessor {
  static final int BATCH_SIZE = 100;

  private final DynamoDbTable<TcgInventoryItem> tcgInventoryTable;
  private final Clock clock;

  AppraiseJobProcessor(DynamoDbTable<TcgInventoryItem> tcgInventoryTable, Clock clock) {
    this.tcgInventoryTable = tcgInventoryTable;
    this.clock = clock;
  }

  BatchResult processBatch(String user, TcgInventoryItem jobItem) {
    var importId = jobItem.getImportId();
    var continuation = jobItem.getContinuation() != null ? jobItem.getContinuation() : 0;

    var importKey =
        Key.builder()
            .partitionValue(TcgInventoryItem.formatUserPk(user))
            .sortValue(TcgInventoryItem.formatImportSk(importId))
            .build();
    var importItem = tcgInventoryTable.getItem(importKey);
    var totalRows = importItem.getRowCount() != null ? importItem.getRowCount() : 0;

    int batchEnd = Math.min(continuation + BATCH_SIZE, totalRows);
    int processed = continuation;

    for (int i = continuation + 1; i <= batchEnd; i++) {
      var rowKey =
          Key.builder()
              .partitionValue(TcgInventoryItem.formatImportRowPk(user, importId))
              .sortValue(TcgInventoryItem.formatImportRowSk(i))
              .build();
      var rowItem = tcgInventoryTable.getItem(rowKey);
      // TODO: replace with real FetchTCG identity resolution and market appraisal (Task 17)
      if (rowItem != null && rowItem.getDecision() == null) {
        rowItem.setDecision("keep");
        rowItem.setDecisionReason("stub appraisal");
        tcgInventoryTable.putItem(rowItem);
      }
      processed = i;
    }

    boolean complete = processed >= totalRows;
    if (complete) {
      importItem.setStatus("review");
      importItem.setKeepCount(totalRows);
      importItem.setUpdatedAt(clock.now());
      tcgInventoryTable.putItem(importItem);
    }

    return new BatchResult(processed, complete);
  }
}
