package com.jordansimsmith.tcginventory.pricing.server;

import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.service.ServiceException;
import com.jordansimsmith.tcginventory.fetchtcg.client.FetchTcgService;
import com.jordansimsmith.tcginventory.pricing.client.GetPriceRequest;
import com.jordansimsmith.tcginventory.pricing.client.GetPriceResponse;
import com.jordansimsmith.tcginventory.pricing.client.PricingService;
import dagger.Module;
import dagger.Provides;
import javax.inject.Named;
import javax.inject.Singleton;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;

@Module
public class PricingModule {
  @Provides
  @Singleton
  PricingService pricingService(
      DynamoDbEnhancedClient dynamoDb,
      @Named("tcgInventoryTableName") String tableName,
      FetchTcgService fetchTcgService) {
    DynamoDbTable<PricingItem> pricingTable =
        dynamoDb.table(tableName, TableSchema.fromBean(PricingItem.class));
    var getPriceService = new GetPriceService(new PricingRepository(pricingTable), fetchTcgService);
    return new PricingService() {
      @Override
      public GetPriceResponse getPrice(RequestContext ctx, GetPriceRequest request)
          throws ServiceException {
        return getPriceService.getPrice(ctx, request);
      }
    };
  }
}
