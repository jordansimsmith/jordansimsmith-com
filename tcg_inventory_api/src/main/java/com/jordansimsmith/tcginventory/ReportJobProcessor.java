package com.jordansimsmith.tcginventory;

import com.jordansimsmith.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryEnhancedRequest;

public class ReportJobProcessor {
  private static final Logger LOGGER = LoggerFactory.getLogger(ReportJobProcessor.class);

  private final DynamoDbTable<TcgInventoryItem> tcgInventoryTable;
  private final Clock clock;

  public ReportJobProcessor(DynamoDbTable<TcgInventoryItem> tcgInventoryTable, Clock clock) {
    this.tcgInventoryTable = tcgInventoryTable;
    this.clock = clock;
  }

  public BatchResult processBatch(String user, TcgInventoryItem jobItem) {
    LOGGER.info("starting report job for user {}", user);

    var asOfAuditUlid = findLatestAuditUlid(user);
    LOGGER.info("captured as-of audit ULID: {}", asOfAuditUlid);

    var now = clock.now();
    var reportItem = TcgInventoryItem.createReport(user, "{}", asOfAuditUlid, now);
    tcgInventoryTable.putItem(reportItem);

    LOGGER.info("wrote report snapshot");
    return new BatchResult(0, true);
  }

  private String findLatestAuditUlid(String user) {
    var request =
        QueryEnhancedRequest.builder()
            .queryConditional(
                QueryConditional.keyEqualTo(
                    Key.builder().partitionValue(TcgInventoryItem.formatAuditPk(user)).build()))
            .scanIndexForward(false)
            .limit(1)
            .build();

    return tcgInventoryTable.query(request).stream()
        .flatMap(page -> page.items().stream())
        .findFirst()
        .map(TcgInventoryItem::getSk)
        .orElse(null);
  }
}
