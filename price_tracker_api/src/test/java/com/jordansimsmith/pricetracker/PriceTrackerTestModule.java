package com.jordansimsmith.pricetracker;

import dagger.Module;
import dagger.Provides;
import javax.inject.Singleton;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;

@Module
public class PriceTrackerTestModule {
  @Provides
  @Singleton
  DynamoDbTable<PriceTrackerItem> priceTrackerTable(DynamoDbEnhancedClient dynamoDbEnhancedClient) {
    var schema = TableSchema.fromBean(PriceTrackerItem.class);
    return dynamoDbEnhancedClient.table("price_tracker", schema);
  }

  @Provides
  @Singleton
  FakePriceClient fakePriceClient() {
    return new FakePriceClient();
  }

  @Provides
  @Singleton
  PriceClient priceClient(FakePriceClient fakePriceClient) {
    return fakePriceClient;
  }

  @Provides
  @Singleton
  FakeProductsFactory fakeProductsFactory() {
    return new FakeProductsFactory();
  }

  @Provides
  @Singleton
  ProductsFactory productsFactory(FakeProductsFactory fakeProductsFactory) {
    return fakeProductsFactory;
  }
}
