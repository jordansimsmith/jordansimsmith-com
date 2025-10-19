package com.jordansimsmith.pricetracker;

import dagger.Module;
import dagger.Provides;
import java.util.Map;
import java.util.Random;
import java.util.random.RandomGenerator;
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
  public PriceClient priceClient() {
    RandomGenerator randomGenerator = new Random();

    var extractors =
        Map.of(
            "www.chemistwarehouse.co.nz", new ChemistWarehousePriceExtractor(),
            "www.nzprotein.co.nz", new NzProteinPriceExtractor(),
            "nzmuscle.co.nz", new NzMusclePriceExtractor());

    return new JsoupPriceClient(randomGenerator, extractors);
  }

  @Provides
  @Singleton
  public ProductsFactory productsFactory() {
    return new ProductsFactoryImpl();
  }
}
