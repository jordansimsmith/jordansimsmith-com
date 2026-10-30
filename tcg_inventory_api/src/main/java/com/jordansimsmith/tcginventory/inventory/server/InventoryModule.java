package com.jordansimsmith.tcginventory.inventory.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.service.ServiceException;
import com.jordansimsmith.tcginventory.inventory.client.FindUnitsRequest;
import com.jordansimsmith.tcginventory.inventory.client.FindUnitsResponse;
import com.jordansimsmith.tcginventory.inventory.client.InventoryService;
import dagger.Module;
import dagger.Provides;
import javax.inject.Named;
import javax.inject.Singleton;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

@Module
public class InventoryModule {
  @Provides
  @Singleton
  InventoryService inventoryService(
      DynamoDbEnhancedClient dynamoDb,
      @Named("tcgInventoryTableName") String tableName,
      ObjectMapper objectMapper,
      S3Presigner s3Presigner,
      @Named("inventoryPhotoBucket") String photoBucket) {
    DynamoDbTable<InventoryUnitItem> unitTable =
        dynamoDb.table(tableName, TableSchema.fromBean(InventoryUnitItem.class));
    var inventoryRepository = new InventoryReadRepository(unitTable);
    var findUnitsService =
        new FindUnitsService(inventoryRepository, objectMapper, s3Presigner, photoBucket);
    return new InventoryService() {
      @Override
      public FindUnitsResponse findUnits(RequestContext ctx, FindUnitsRequest request)
          throws ServiceException {
        return findUnitsService.findUnits(ctx, request);
      }
    };
  }
}
