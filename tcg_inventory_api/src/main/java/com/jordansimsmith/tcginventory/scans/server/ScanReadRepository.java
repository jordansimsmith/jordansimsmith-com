package com.jordansimsmith.tcginventory.scans.server;

import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryEnhancedRequest;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

public class ScanReadRepository {
  public record ScanPage(List<ScanItem> items, Map<String, AttributeValue> lastEvaluatedKey) {}

  private final DynamoDbTable<ScanItem> scanTable;
  private final DynamoDbTable<ScanRowItem> scanRowTable;

  public ScanReadRepository(
      DynamoDbTable<ScanItem> scanTable, DynamoDbTable<ScanRowItem> scanRowTable) {
    this.scanTable = scanTable;
    this.scanRowTable = scanRowTable;
  }

  public ScanPage findScans(
      String user, int limit, @Nullable Map<String, AttributeValue> exclusiveStartKey) {
    var requestBuilder =
        QueryEnhancedRequest.builder()
            .queryConditional(
                QueryConditional.sortBeginsWith(
                    Key.builder()
                        .partitionValue(ScanItem.formatPk(user))
                        .sortValue(ScanItem.SCAN_PREFIX)
                        .build()))
            .scanIndexForward(false)
            .limit(limit);
    if (exclusiveStartKey != null && !exclusiveStartKey.isEmpty()) {
      requestBuilder.exclusiveStartKey(exclusiveStartKey);
    }

    var page = scanTable.query(requestBuilder.build()).stream().findFirst().orElse(null);
    if (page == null) {
      return new ScanPage(List.of(), Map.of());
    }
    return new ScanPage(page.items(), page.lastEvaluatedKey());
  }

  public List<ScanRowItem> findScanRows(String user, String scanId) {
    var request =
        QueryEnhancedRequest.builder()
            .queryConditional(
                QueryConditional.sortBeginsWith(
                    Key.builder()
                        .partitionValue(ScanRowItem.formatPk(user, scanId))
                        .sortValue(ScanRowItem.ROW_PREFIX)
                        .build()))
            .scanIndexForward(true)
            .build();
    return scanRowTable.query(request).stream().flatMap(page -> page.items().stream()).toList();
  }

  @Nullable
  public ScanItem getScan(String user, String scanId) {
    return scanTable.getItem(
        Key.builder()
            .partitionValue(ScanItem.formatPk(user))
            .sortValue(ScanItem.formatSk(scanId))
            .build());
  }
}
