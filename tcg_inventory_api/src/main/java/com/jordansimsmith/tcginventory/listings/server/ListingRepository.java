package com.jordansimsmith.tcginventory.listings.server;

import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.model.BatchGetItemEnhancedRequest;
import software.amazon.awssdk.enhanced.dynamodb.model.GetItemEnhancedRequest;
import software.amazon.awssdk.enhanced.dynamodb.model.ReadBatch;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.Put;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;

public class ListingRepository {
  private final DynamoDbTable<CurrentListingItem> currentListingTable;
  private final DynamoDbTable<ListingAssociationItem> listingAssociationTable;
  private final DynamoDbEnhancedClient dynamoDbEnhancedClient;
  private final DynamoDbClient dynamoDbClient;
  private final String tableName;

  public ListingRepository(
      DynamoDbTable<CurrentListingItem> currentListingTable,
      DynamoDbTable<ListingAssociationItem> listingAssociationTable,
      DynamoDbEnhancedClient dynamoDbEnhancedClient,
      DynamoDbClient dynamoDbClient,
      String tableName) {
    this.currentListingTable = currentListingTable;
    this.listingAssociationTable = listingAssociationTable;
    this.dynamoDbEnhancedClient = dynamoDbEnhancedClient;
    this.dynamoDbClient = dynamoDbClient;
    this.tableName = tableName;
  }

  @Nullable
  public CurrentListingItem getCurrentListing(String user, String skuId) {
    var key =
        Key.builder()
            .partitionValue(CurrentListingItem.formatPk(user))
            .sortValue(CurrentListingItem.formatSk(skuId))
            .build();
    return currentListingTable.getItem(request -> request.key(key).consistentRead(true));
  }

  public List<ListingAssociationItem> findListings(String user, List<Integer> listingIds) {
    var readBatch =
        ReadBatch.builder(ListingAssociationItem.class)
            .mappedTableResource(listingAssociationTable);
    for (var listingId : listingIds) {
      readBatch.addGetItem(
          GetItemEnhancedRequest.builder()
              .consistentRead(true)
              .key(
                  Key.builder()
                      .partitionValue(ListingAssociationItem.formatPk(user))
                      .sortValue(ListingAssociationItem.formatSk(listingId))
                      .build())
              .build());
    }
    var request = BatchGetItemEnhancedRequest.builder().readBatches(readBatch.build()).build();
    var items = new ArrayList<ListingAssociationItem>(listingIds.size());
    for (var page : dynamoDbEnhancedClient.batchGetItem(request)) {
      page.resultsForTable(listingAssociationTable).forEach(items::add);
    }
    return items;
  }

  public void saveListing(String user, String skuId, int listingId) {
    var currentListing = CurrentListingItem.create(user, skuId, listingId);
    var listingAssociation = ListingAssociationItem.create(user, skuId, listingId);
    dynamoDbClient.transactWriteItems(
        TransactWriteItemsRequest.builder()
            .transactItems(
                TransactWriteItem.builder()
                    .put(
                        Put.builder()
                            .tableName(tableName)
                            .item(currentListingTable.tableSchema().itemToMap(currentListing, true))
                            .build())
                    .build(),
                TransactWriteItem.builder()
                    .put(
                        Put.builder()
                            .tableName(tableName)
                            .item(
                                listingAssociationTable
                                    .tableSchema()
                                    .itemToMap(listingAssociation, true))
                            .build())
                    .build())
            .build());
  }

  public void deleteCurrentListing(String user, String skuId) {
    var key =
        Key.builder()
            .partitionValue(CurrentListingItem.formatPk(user))
            .sortValue(CurrentListingItem.formatSk(skuId))
            .build();
    currentListingTable.deleteItem(key);
  }
}
