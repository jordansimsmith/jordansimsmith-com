package com.jordansimsmith.tcginventory.fetchtcg.server;

import javax.annotation.Nullable;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;

public class FetchTcgAccountRepository {
  private final DynamoDbTable<FetchTcgAccountItem> accountTable;

  public FetchTcgAccountRepository(DynamoDbTable<FetchTcgAccountItem> accountTable) {
    this.accountTable = accountTable;
  }

  @Nullable
  public FetchTcgAccountItem get(String user) {
    var key =
        Key.builder()
            .partitionValue(FetchTcgAccountItem.formatPk(user))
            .sortValue(FetchTcgAccountItem.formatSk())
            .build();
    return accountTable.getItem(request -> request.key(key).consistentRead(true));
  }

  public void update(FetchTcgAccountItem item) {
    accountTable.putItem(item);
  }
}
