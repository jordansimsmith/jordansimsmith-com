package com.jordansimsmith.tcginventory.orders.server;

import javax.annotation.Nullable;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;

public class OrderSettingsRepository {
  private final DynamoDbTable<OrderSettingsItem> settingsTable;

  public OrderSettingsRepository(DynamoDbTable<OrderSettingsItem> settingsTable) {
    this.settingsTable = settingsTable;
  }

  @Nullable
  public OrderSettingsItem get(String user) {
    var key =
        Key.builder()
            .partitionValue(OrderSettingsItem.formatPk(user))
            .sortValue(OrderSettingsItem.formatSk())
            .build();
    return settingsTable.getItem(request -> request.key(key).consistentRead(true));
  }

  public void update(OrderSettingsItem item) {
    settingsTable.updateItem(item);
  }
}
