package com.jordansimsmith.tcginventory.catalog;

import javax.annotation.Nullable;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryEnhancedRequest;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;

public class CatalogRepository {
  private final DynamoDbTable<CatalogSnapshotItem> catalogSnapshotTable;
  private final DynamoDbClient dynamoDbClient;

  public CatalogRepository(
      DynamoDbTable<CatalogSnapshotItem> catalogSnapshotTable, DynamoDbClient dynamoDbClient) {
    this.catalogSnapshotTable = catalogSnapshotTable;
    this.dynamoDbClient = dynamoDbClient;
  }

  @Nullable
  public CatalogSnapshotItem getSnapshot(String game, String snapshotId) {
    return catalogSnapshotTable.getItem(
        request ->
            request
                .key(
                    Key.builder()
                        .partitionValue(CatalogSnapshotItem.formatPk(game))
                        .sortValue(CatalogSnapshotItem.formatSk(snapshotId))
                        .build())
                .consistentRead(true));
  }

  @Nullable
  public CatalogSnapshotItem getLatestSnapshot(String game) {
    return catalogSnapshotTable
        .query(
            QueryEnhancedRequest.builder()
                .queryConditional(
                    QueryConditional.sortBeginsWith(
                        Key.builder()
                            .partitionValue(CatalogSnapshotItem.formatPk(game))
                            .sortValue(CatalogSnapshotItem.SNAPSHOT_PREFIX)
                            .build()))
                .scanIndexForward(false)
                .limit(1)
                .consistentRead(true)
                .build())
        .stream()
        .findFirst()
        .flatMap(page -> page.items().stream().findFirst())
        .orElse(null);
  }

  public CatalogSnapshotItem createSnapshot(CatalogSnapshotItem item) {
    try {
      dynamoDbClient.putItem(
          PutItemRequest.builder()
              .tableName(catalogSnapshotTable.tableName())
              .item(catalogSnapshotTable.tableSchema().itemToMap(item, true))
              .conditionExpression("attribute_not_exists(" + CatalogSnapshotItem.PK + ")")
              .build());
      return item;
    } catch (ConditionalCheckFailedException e) {
      var existing = getSnapshot(item.getGame(), item.getSnapshotId());
      if (existing == null) {
        throw e;
      }
      return existing;
    }
  }
}
