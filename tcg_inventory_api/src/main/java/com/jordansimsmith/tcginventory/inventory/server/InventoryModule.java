package com.jordansimsmith.tcginventory.inventory.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.service.ServiceException;
import com.jordansimsmith.tcginventory.inventory.client.DeleteUnitRequest;
import com.jordansimsmith.tcginventory.inventory.client.DeleteUnitResponse;
import com.jordansimsmith.tcginventory.inventory.client.FindSkusRequest;
import com.jordansimsmith.tcginventory.inventory.client.FindSkusResponse;
import com.jordansimsmith.tcginventory.inventory.client.FindUnitsRequest;
import com.jordansimsmith.tcginventory.inventory.client.FindUnitsResponse;
import com.jordansimsmith.tcginventory.inventory.client.GetSkuRequest;
import com.jordansimsmith.tcginventory.inventory.client.GetSkuResponse;
import com.jordansimsmith.tcginventory.inventory.client.GetUnitRequest;
import com.jordansimsmith.tcginventory.inventory.client.GetUnitResponse;
import com.jordansimsmith.tcginventory.inventory.client.InventoryService;
import com.jordansimsmith.time.Clock;
import com.jordansimsmith.ulid.UlidGenerator;
import dagger.Module;
import dagger.Provides;
import javax.inject.Named;
import javax.inject.Singleton;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

@Module
public class InventoryModule {
  @Provides
  @Singleton
  InventoryService inventoryService(
      DynamoDbEnhancedClient dynamoDb,
      DynamoDbClient dynamoDbClient,
      @Named("tcgInventoryTableName") String tableName,
      ObjectMapper objectMapper,
      Clock clock,
      UlidGenerator ulidGenerator) {
    DynamoDbTable<InventorySkuItem> skuTable =
        dynamoDb.table(tableName, TableSchema.fromBean(InventorySkuItem.class));
    DynamoDbTable<InventoryUnitItem> unitTable =
        dynamoDb.table(tableName, TableSchema.fromBean(InventoryUnitItem.class));
    var inventoryRepository = new InventoryReadRepository(skuTable, unitTable);
    var findSkusService = new FindSkusService(inventoryRepository, objectMapper);
    var getSkuService = new GetSkuService(inventoryRepository);
    var getUnitService = new GetUnitService(inventoryRepository);
    var findUnitsService = new FindUnitsService(inventoryRepository, objectMapper);
    var deleteUnitService =
        new DeleteUnitService(
            inventoryRepository,
            new InventoryMutationRepository(dynamoDbClient, tableName, clock, ulidGenerator));
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
      public GetUnitResponse getUnit(RequestContext ctx, GetUnitRequest request)
          throws ServiceException {
        return getUnitService.getUnit(ctx, request);
      }

      @Override
      public FindUnitsResponse findUnits(RequestContext ctx, FindUnitsRequest request)
          throws ServiceException {
        return findUnitsService.findUnits(ctx, request);
      }

      @Override
      public DeleteUnitResponse deleteUnit(RequestContext ctx, DeleteUnitRequest request)
          throws ServiceException {
        return deleteUnitService.deleteUnit(ctx, request);
      }
    };
  }
}
