package com.jordansimsmith.tcginventory.inventory.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.service.ServiceException;
import com.jordansimsmith.tcginventory.inventory.client.FindSkusRequest;
import com.jordansimsmith.tcginventory.inventory.client.FindSkusResponse;
import com.jordansimsmith.tcginventory.inventory.client.FindUnitsRequest;
import com.jordansimsmith.tcginventory.inventory.client.FindUnitsResponse;
import com.jordansimsmith.tcginventory.inventory.client.GetSkuRequest;
import com.jordansimsmith.tcginventory.inventory.client.GetSkuResponse;
import com.jordansimsmith.tcginventory.inventory.client.InventoryService;
import dagger.Module;
import dagger.Provides;
import javax.inject.Named;
import javax.inject.Singleton;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;

@Module
public class InventoryModule {
  @Provides
  @Singleton
  InventoryService inventoryService(
      DynamoDbEnhancedClient dynamoDb,
      @Named("tcgInventoryTableName") String tableName,
      ObjectMapper objectMapper) {
    DynamoDbTable<InventorySkuItem> skuTable =
        dynamoDb.table(tableName, TableSchema.fromBean(InventorySkuItem.class));
    DynamoDbTable<InventoryUnitItem> unitTable =
        dynamoDb.table(tableName, TableSchema.fromBean(InventoryUnitItem.class));
    var inventoryRepository = new InventoryReadRepository(skuTable, unitTable);
    var findSkusService = new FindSkusService(inventoryRepository, objectMapper);
    var getSkuService = new GetSkuService(inventoryRepository);
    var findUnitsService = new FindUnitsService(inventoryRepository, objectMapper);
    return new InventoryService() {
      @Override
      public FindSkusResponse findSkus(RequestContext ctx, FindSkusRequest request)
          throws ServiceException {
        return findSkusService.findSkus(ctx, request);
      }

      @Override
      public GetSkuResponse getSku(RequestContext ctx, GetSkuRequest request)
          throws ServiceException {
        return getSkuService.getSku(ctx, request);
      }

      @Override
      public FindUnitsResponse findUnits(RequestContext ctx, FindUnitsRequest request)
          throws ServiceException {
        return findUnitsService.findUnits(ctx, request);
      }
    };
  }
}
