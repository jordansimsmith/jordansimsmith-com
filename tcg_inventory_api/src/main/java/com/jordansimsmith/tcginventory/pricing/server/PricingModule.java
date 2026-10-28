package com.jordansimsmith.tcginventory.pricing.server;

import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.service.ServiceException;
import com.jordansimsmith.tcginventory.fetchtcg.client.FetchTcgService;
import com.jordansimsmith.tcginventory.pricing.client.GetPriceRequest;
import com.jordansimsmith.tcginventory.pricing.client.GetPriceResponse;
import com.jordansimsmith.tcginventory.pricing.client.GetPricingRequest;
import com.jordansimsmith.tcginventory.pricing.client.GetPricingResponse;
import com.jordansimsmith.tcginventory.pricing.client.PricingService;
import com.jordansimsmith.tcginventory.pricing.client.UpdatePricingRequest;
import com.jordansimsmith.tcginventory.pricing.client.UpdatePricingResponse;
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
    var pricingRepository = new PricingRepository(pricingTable);
    var getPriceService = new GetPriceService(pricingRepository, fetchTcgService);
    var getPricingService = new GetPricingService(pricingRepository);
    var updatePricingService = new UpdatePricingService(pricingRepository);
    return new PricingService() {
      @Override
      public GetPricingResponse getPricing(RequestContext ctx, GetPricingRequest request)
          throws ServiceException {
        return getPricingService.getPricing(ctx, request);
      }

      @Override
      public UpdatePricingResponse updatePricing(RequestContext ctx, UpdatePricingRequest request)
          throws ServiceException {
        return updatePricingService.updatePricing(ctx, request);
      }

      @Override
      public GetPriceResponse getPrice(RequestContext ctx, GetPriceRequest request)
          throws ServiceException {
        return getPriceService.getPrice(ctx, request);
      }
    };
  }
}
