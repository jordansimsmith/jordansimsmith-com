package com.jordansimsmith.tcginventory.listings.server;

import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.service.ServiceException;
import com.jordansimsmith.tcginventory.fetchtcg.client.FetchTcgService;
import com.jordansimsmith.tcginventory.inventory.client.InventoryService;
import com.jordansimsmith.tcginventory.listings.client.FindListingsRequest;
import com.jordansimsmith.tcginventory.listings.client.FindListingsResponse;
import com.jordansimsmith.tcginventory.listings.client.ListingService;
import com.jordansimsmith.tcginventory.listings.client.UpdateListingRequest;
import com.jordansimsmith.tcginventory.listings.client.UpdateListingResponse;
import com.jordansimsmith.tcginventory.photos.client.PhotosService;
import dagger.Module;
import dagger.Provides;
import javax.inject.Named;
import javax.inject.Singleton;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

@Module
public class ListingsModule {
  @Provides
  @Singleton
  ListingService listingService(
      InventoryService inventoryService,
      PhotosService photosService,
      FetchTcgService fetchTcgService,
      DynamoDbEnhancedClient dynamoDbEnhancedClient,
      DynamoDbClient dynamoDbClient,
      @Named("tcgInventoryTableName") String tableName) {
    DynamoDbTable<CurrentListingItem> currentListingTable =
        dynamoDbEnhancedClient.table(tableName, TableSchema.fromBean(CurrentListingItem.class));
    DynamoDbTable<ListingAssociationItem> listingAssociationTable =
        dynamoDbEnhancedClient.table(tableName, TableSchema.fromBean(ListingAssociationItem.class));
    var listingRepository =
        new ListingRepository(
            currentListingTable,
            listingAssociationTable,
            dynamoDbEnhancedClient,
            dynamoDbClient,
            tableName);
    var findListingsService = new FindListingsService(listingRepository);
    var updateListingService =
        new UpdateListingService(
            listingRepository, inventoryService, photosService, fetchTcgService);
    return new ListingService() {
      @Override
      public UpdateListingResponse updateListing(RequestContext ctx, UpdateListingRequest request)
          throws ServiceException {
        return updateListingService.updateListing(ctx, request);
      }

      @Override
      public FindListingsResponse findListings(RequestContext ctx, FindListingsRequest request)
          throws ServiceException {
        return findListingsService.findListings(ctx, request);
      }
    };
  }
}
