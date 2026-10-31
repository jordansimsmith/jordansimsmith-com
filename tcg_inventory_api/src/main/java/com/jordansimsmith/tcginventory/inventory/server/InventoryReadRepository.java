package com.jordansimsmith.tcginventory.inventory.server;

import java.util.Map;
import javax.annotation.Nullable;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.model.Page;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryEnhancedRequest;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

public class InventoryReadRepository {
  private final DynamoDbTable<InventorySkuItem> skuTable;
  private final DynamoDbTable<InventoryUnitItem> unitTable;

  public InventoryReadRepository(
      DynamoDbTable<InventorySkuItem> skuTable, DynamoDbTable<InventoryUnitItem> unitTable) {
    this.skuTable = skuTable;
    this.unitTable = unitTable;
  }

  @Nullable
  public Page<InventorySkuItem> findSkus(
      String user,
      String game,
      String search,
      int limit,
      @Nullable Map<String, AttributeValue> continuation) {
    var request =
        QueryEnhancedRequest.builder()
            .queryConditional(
                QueryConditional.sortBeginsWith(
                    Key.builder()
                        .partitionValue(InventorySkuItem.formatGsi2pk(user))
                        .sortValue(InventorySkuItem.formatGsi2skPrefix(game, search))
                        .build()))
            .scanIndexForward(true)
            .limit(limit)
            .exclusiveStartKey(continuation)
            .build();
    return skuTable.index("gsi2").query(request).stream().findFirst().orElse(null);
  }

  @Nullable
  public InventorySkuItem getSku(String user, String skuId) {
    return skuTable.getItem(
        Key.builder()
            .partitionValue(InventorySkuItem.formatPk(user, skuId))
            .sortValue("SKU")
            .build());
  }

  @Nullable
  public InventoryUnitItem getUnit(String user, String skuId, int sequenceNumber) {
    var key =
        Key.builder()
            .partitionValue(InventoryUnitItem.formatPk(user, skuId))
            .sortValue(InventoryUnitItem.formatSk(sequenceNumber))
            .build();
    return unitTable.getItem(request -> request.key(key).consistentRead(true));
  }

  public Page<InventoryUnitItem> findUnits(
      String user, String skuId, int limit, @Nullable Map<String, AttributeValue> continuation) {
    var request =
        QueryEnhancedRequest.builder()
            .queryConditional(
                QueryConditional.sortBeginsWith(
                    Key.builder()
                        .partitionValue(InventoryUnitItem.formatPk(user, skuId))
                        .sortValue(InventoryUnitItem.UNIT_PREFIX)
                        .build()))
            .consistentRead(true)
            .scanIndexForward(true)
            .limit(limit)
            .exclusiveStartKey(continuation)
            .build();
    return unitTable.query(request).stream().findFirst().orElse(null);
  }
}
