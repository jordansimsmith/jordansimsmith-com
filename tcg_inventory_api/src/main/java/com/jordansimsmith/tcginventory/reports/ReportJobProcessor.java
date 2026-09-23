package com.jordansimsmith.tcginventory.reports;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jordansimsmith.tcginventory.AuditItem;
import com.jordansimsmith.tcginventory.BatchResult;
import com.jordansimsmith.tcginventory.JobItem;
import com.jordansimsmith.tcginventory.TcgInventoryTable;
import com.jordansimsmith.tcginventory.inventory.InventoryRepository;
import com.jordansimsmith.tcginventory.inventory.SkuItem;
import com.jordansimsmith.tcginventory.orders.OrderItem;
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

  private final DynamoDbTable<ReportItem> reportTable;
  private final InventoryRepository inventoryRepository;
  private final DynamoDbTable<AuditItem> auditTable;
  private final DynamoDbTable<OrderItem> orderTable;
  private final DynamoDbIndex<SkuItem> gsi2Index;
  private final ObjectMapper objectMapper;
  private final Clock clock;

  public ReportJobProcessor(
      DynamoDbTable<ReportItem> reportTable,
      InventoryRepository inventoryRepository,
      DynamoDbTable<AuditItem> auditTable,
      DynamoDbTable<SkuItem> skuTable,
      DynamoDbTable<OrderItem> orderTable,
      ObjectMapper objectMapper,
      Clock clock) {
    this.reportTable = reportTable;
    this.inventoryRepository = inventoryRepository;
    this.auditTable = auditTable;
    this.orderTable = orderTable;
    this.gsi2Index = skuTable.index(TcgInventoryTable.GSI2_NAME);
    this.objectMapper = objectMapper;
    this.clock = clock;
  }

  public BatchResult processBatch(String user, JobItem jobItem) {
    LOGGER.info("starting report job for user {}", user);

    var asOfAuditUlid = findLatestAuditUlid(user);
    LOGGER.info("captured as-of audit ULID: {}", asOfAuditUlid);

    var now = clock.now();
    var accumulator = new ReportAccumulator(now, objectMapper);

    for (var sku : pageGsi2Skus(user)) {
      var units = inventoryRepository.findUnits(user, sku.getSkuId());
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
              accumulator.toAgingBands(),
              accumulator.toRevenueByMonth(),
              accumulator.toIntakeVsSalesByWeek());
      var reportJson = objectMapper.writeValueAsString(payload);
      var reportItem = ReportItem.create(user, reportJson, asOfAuditUlid, now);
      reportTable.putItem(reportItem);
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
                    Key.builder().partitionValue(AuditItem.formatPk(user)).build()))
            .scanIndexForward(false)
            .limit(1)
            .build();

    return auditTable.query(request).stream()
        .flatMap(page -> page.items().stream())
        .findFirst()
        .map(AuditItem::getSk)
        .orElse(null);
  }

  private List<SkuItem> pageGsi2Skus(String user) {
    var gsi2pk = SkuItem.formatGsi2pk(user);
    var request =
        QueryEnhancedRequest.builder()
            .queryConditional(
                QueryConditional.keyEqualTo(Key.builder().partitionValue(gsi2pk).build()))
            .scanIndexForward(true)
            .build();

    return gsi2Index.query(request).stream().flatMap(page -> page.items().stream()).toList();
  }

  private List<OrderItem> pageOrders(String user) {
    var request =
        QueryEnhancedRequest.builder()
            .queryConditional(
                QueryConditional.sortBeginsWith(
                    Key.builder()
                        .partitionValue(SkuItem.formatUserPk(user))
                        .sortValue(OrderItem.ORDER_PREFIX)
                        .build()))
            .scanIndexForward(true)
            .build();

    return orderTable.query(request).stream().flatMap(page -> page.items().stream()).toList();
  }
}
