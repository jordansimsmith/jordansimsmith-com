package com.jordansimsmith.pricetracker;

import dagger.Module;
import dagger.Provides;
import javax.inject.Singleton;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;

@Module
public class PriceTrackerModule {
  @Provides
  @Singleton
  public DynamoDbTable<PriceTrackerItem> immersionTrackerTable(
      DynamoDbEnhancedClient dynamoDbEnhancedClient) {
    var schema = TableSchema.fromBean(PriceTrackerItem.class);
    return dynamoDbEnhancedClient.table("price_tracker", schema);
  }

  @Provides
  @Singleton
  public ChemistWarehouseClient chemistWarehouseClient() {
    return new JsoupChemistWarehouseClient();
  }

  @Provides
  @Singleton
  public ProductsFactory productsFactory() {
    return new ProductsFactoryImpl();
  }
}
