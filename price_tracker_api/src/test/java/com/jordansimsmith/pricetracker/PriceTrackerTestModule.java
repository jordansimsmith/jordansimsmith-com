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
  public DynamoDbTable<PriceTrackerItem> priceTrackerTable(
      DynamoDbEnhancedClient dynamoDbEnhancedClient) {
    var schema = TableSchema.fromBean(PriceTrackerItem.class);
    return dynamoDbEnhancedClient.table("price_tracker", schema);
  }

  @Provides
  @Singleton
  public FakeChemistWarehouseClient fakeChemistWarehouseClient() {
    return new FakeChemistWarehouseClient();
  }

  @Provides
  @Singleton
  public ChemistWarehouseClient chemistWarehouseClient(
      FakeChemistWarehouseClient fakeChemistWarehouseClient) {
    return fakeChemistWarehouseClient;
  }

  @Provides
  @Singleton
  public FakeProductsFactory fakeProductsFactory() {
    return new FakeProductsFactory();
  }

  @Provides
  @Singleton
  public ProductsFactory productsFactory(FakeProductsFactory fakeProductsFactory) {
    return fakeProductsFactory;
  }
}
