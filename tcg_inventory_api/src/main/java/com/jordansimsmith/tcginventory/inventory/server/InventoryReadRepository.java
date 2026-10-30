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
  private final DynamoDbTable<InventoryUnitItem> unitTable;

  public InventoryReadRepository(DynamoDbTable<InventoryUnitItem> unitTable) {
    this.unitTable = unitTable;
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
