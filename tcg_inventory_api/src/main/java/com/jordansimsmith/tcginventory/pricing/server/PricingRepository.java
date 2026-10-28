package com.jordansimsmith.tcginventory.pricing.server;

import javax.annotation.Nullable;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;

public class PricingRepository {
  private final DynamoDbTable<PricingItem> pricingTable;

  public PricingRepository(DynamoDbTable<PricingItem> pricingTable) {
    this.pricingTable = pricingTable;
  }

  @Nullable
  public PricingItem get(String user) {
    var key =
        Key.builder()
            .partitionValue(PricingItem.formatPk(user))
            .sortValue(PricingItem.formatSk())
            .build();
    return pricingTable.getItem(request -> request.key(key).consistentRead(true));
  }

  public void update(PricingItem item) {
    pricingTable.updateItem(item);
  }
}
