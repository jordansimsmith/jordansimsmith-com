package com.jordansimsmith.tcginventory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jordansimsmith.time.Clock;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbIndex;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryEnhancedRequest;

public class ReportJobProcessor {
  private static final Logger LOGGER = LoggerFactory.getLogger(ReportJobProcessor.class);

  private final DynamoDbTable<TcgInventoryItem> tcgInventoryTable;
  private final DynamoDbIndex<TcgInventoryItem> gsi2Index;
  private final ObjectMapper objectMapper;
  private final Clock clock;

  public ReportJobProcessor(
      DynamoDbTable<TcgInventoryItem> tcgInventoryTable, ObjectMapper objectMapper, Clock clock) {
    this.tcgInventoryTable = tcgInventoryTable;
    this.gsi2Index = tcgInventoryTable.index(TcgInventoryItem.GSI2_NAME);
    this.objectMapper = objectMapper;
    this.clock = clock;
  }

  public BatchResult processBatch(String user, TcgInventoryItem jobItem) {
    LOGGER.info("starting report job for user {}", user);

    var asOfAuditUlid = findLatestAuditUlid(user);
    LOGGER.info("captured as-of audit ULID: {}", asOfAuditUlid);

    var now = clock.now();
    var accumulator = new ReportAccumulator(now);

    for (var sku : pageGsi2Skus(user)) {
      var units = queryUnits(user, sku.getSkuId());
      accumulator.addSku(sku, units);
    }

    for (var order : pageOrders(user)) {
      accumulator.addOrder(order);
    }

    try {
      var payload =
          new ReportPayload(
              accumulator.toTotals(),
              accumulator.toTopSets(),
              accumulator.toPriceBuckets(),
              accumulator.toTopHits(),
              accumulator.toAgingBands());
      var reportJson = objectMapper.writeValueAsString(payload);
      var reportItem = TcgInventoryItem.createReport(user, reportJson, asOfAuditUlid, now);
      tcgInventoryTable.putItem(reportItem);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }

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

  private List<TcgInventoryItem> pageGsi2Skus(String user) {
    var gsi2pk = TcgInventoryItem.formatGsi2pk(user);
    var request =
        QueryEnhancedRequest.builder()
            .queryConditional(
                QueryConditional.keyEqualTo(Key.builder().partitionValue(gsi2pk).build()))
            .scanIndexForward(true)
            .build();

    return gsi2Index.query(request).stream().flatMap(page -> page.items().stream()).toList();
  }

  private List<TcgInventoryItem> queryUnits(String user, String skuId) {
    var skuPk = TcgInventoryItem.formatSkuPk(user, skuId);
    var request =
        QueryEnhancedRequest.builder()
            .queryConditional(
                QueryConditional.sortBeginsWith(
                    Key.builder()
                        .partitionValue(skuPk)
                        .sortValue(TcgInventoryItem.UNIT_PREFIX)
                        .build()))
            .scanIndexForward(true)
            .build();

    return tcgInventoryTable.query(request).stream()
        .flatMap(page -> page.items().stream())
        .toList();
  }

  private List<TcgInventoryItem> pageOrders(String user) {
    var request =
        QueryEnhancedRequest.builder()
            .queryConditional(
                QueryConditional.sortBeginsWith(
                    Key.builder()
                        .partitionValue(TcgInventoryItem.formatUserPk(user))
                        .sortValue(TcgInventoryItem.ORDER_PREFIX)
                        .build()))
            .scanIndexForward(true)
            .build();

    return tcgInventoryTable.query(request).stream()
        .flatMap(page -> page.items().stream())
        .toList();
  }
}
