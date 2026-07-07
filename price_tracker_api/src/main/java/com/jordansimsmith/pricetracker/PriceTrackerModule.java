package com.jordansimsmith.pricetracker;

import dagger.Module;
import dagger.Provides;
import java.net.URI;
import java.util.Map;
import java.util.Random;
import java.util.random.RandomGenerator;
import javax.inject.Named;
import javax.inject.Singleton;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;

@Module
public class PriceTrackerModule {
  @Provides
  @Singleton
  DynamoDbTable<PriceTrackerItem> immersionTrackerTable(
      DynamoDbEnhancedClient dynamoDbEnhancedClient) {
    var schema = TableSchema.fromBean(PriceTrackerItem.class);
    return dynamoDbEnhancedClient.table("price_tracker", schema);
  }

  @Provides
  @Singleton
  @Named("chemistWarehouseBaseUri")
  URI chemistWarehouseBaseUri() {
    var chemistWarehouseBaseUrl = System.getenv("PRICE_TRACKER_CHEMIST_WAREHOUSE_BASE_URL");
    if (chemistWarehouseBaseUrl == null || chemistWarehouseBaseUrl.isBlank()) {
      chemistWarehouseBaseUrl = "https://www.chemistwarehouse.co.nz";
    }
    return URI.create(chemistWarehouseBaseUrl);
  }

  @Provides
  @Singleton
  @Named("nzProteinBaseUri")
  URI nzProteinBaseUri() {
    var nzProteinBaseUrl = System.getenv("PRICE_TRACKER_NZ_PROTEIN_BASE_URL");
    if (nzProteinBaseUrl == null || nzProteinBaseUrl.isBlank()) {
      nzProteinBaseUrl = "https://www.nzprotein.co.nz";
    }
    return URI.create(nzProteinBaseUrl);
  }

  @Provides
  @Singleton
  @Named("sportsfuelBaseUri")
  URI sportsfuelBaseUri() {
    var sportsfuelBaseUrl = System.getenv("PRICE_TRACKER_SPORTSFUEL_BASE_URL");
    if (sportsfuelBaseUrl == null || sportsfuelBaseUrl.isBlank()) {
      sportsfuelBaseUrl = "https://www.sportsfuel.co.nz";
    }
    return URI.create(sportsfuelBaseUrl);
  }

  @Provides
  @Singleton
  PriceClient priceClient(
      @Named("chemistWarehouseBaseUri") URI chemistWarehouseBaseUri,
      @Named("nzProteinBaseUri") URI nzProteinBaseUri,
      @Named("sportsfuelBaseUri") URI sportsfuelBaseUri) {
    RandomGenerator randomGenerator = new Random();

    var extractors =
        Map.of(
            chemistWarehouseBaseUri.getHost(),
            new ChemistWarehousePriceExtractor(),
            nzProteinBaseUri.getHost(),
            new NzProteinPriceExtractor(),
            sportsfuelBaseUri.getHost(),
            new SportsfuelPriceExtractor());

    return new JsoupPriceClient(randomGenerator, extractors);
  }

  @Provides
  @Singleton
  ProductsFactory productsFactory(
      @Named("chemistWarehouseBaseUri") URI chemistWarehouseBaseUri,
      @Named("nzProteinBaseUri") URI nzProteinBaseUri,
      @Named("sportsfuelBaseUri") URI sportsfuelBaseUri) {
    return new ProductsFactoryImpl(chemistWarehouseBaseUri, nzProteinBaseUri, sportsfuelBaseUri);
  }
}
