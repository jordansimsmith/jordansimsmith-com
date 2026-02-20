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
  public DynamoDbTable<PriceTrackerItem> immersionTrackerTable(
      DynamoDbEnhancedClient dynamoDbEnhancedClient) {
    var schema = TableSchema.fromBean(PriceTrackerItem.class);
    return dynamoDbEnhancedClient.table("price_tracker", schema);
  }

  @Provides
  @Singleton
  @Named("chemistWarehouseBaseUri")
  public URI chemistWarehouseBaseUri() {
    var chemistWarehouseBaseUrl = System.getenv("PRICE_TRACKER_CHEMIST_WAREHOUSE_BASE_URL");
    if (chemistWarehouseBaseUrl == null || chemistWarehouseBaseUrl.isBlank()) {
      chemistWarehouseBaseUrl = "https://www.chemistwarehouse.co.nz";
    }
    return URI.create(chemistWarehouseBaseUrl);
  }

  @Provides
  @Singleton
  @Named("nzProteinBaseUri")
  public URI nzProteinBaseUri() {
    var nzProteinBaseUrl = System.getenv("PRICE_TRACKER_NZ_PROTEIN_BASE_URL");
    if (nzProteinBaseUrl == null || nzProteinBaseUrl.isBlank()) {
      nzProteinBaseUrl = "https://www.nzprotein.co.nz";
    }
    return URI.create(nzProteinBaseUrl);
  }

  @Provides
  @Singleton
  @Named("nzMuscleBaseUri")
  public URI nzMuscleBaseUri() {
    var nzMuscleBaseUrl = System.getenv("PRICE_TRACKER_NZ_MUSCLE_BASE_URL");
    if (nzMuscleBaseUrl == null || nzMuscleBaseUrl.isBlank()) {
      nzMuscleBaseUrl = "https://nzmuscle.co.nz";
    }
    return URI.create(nzMuscleBaseUrl);
  }

  @Provides
  @Singleton
  public PriceClient priceClient(
      @Named("chemistWarehouseBaseUri") URI chemistWarehouseBaseUri,
      @Named("nzProteinBaseUri") URI nzProteinBaseUri,
      @Named("nzMuscleBaseUri") URI nzMuscleBaseUri) {
    RandomGenerator randomGenerator = new Random();

    var extractors =
        Map.of(
            chemistWarehouseBaseUri.getHost(),
            new ChemistWarehousePriceExtractor(),
            nzProteinBaseUri.getHost(),
            new NzProteinPriceExtractor(),
            nzMuscleBaseUri.getHost(),
            new NzMusclePriceExtractor());

    return new JsoupPriceClient(randomGenerator, extractors);
  }

  @Provides
  @Singleton
  public ProductsFactory productsFactory(
      @Named("chemistWarehouseBaseUri") URI chemistWarehouseBaseUri,
      @Named("nzProteinBaseUri") URI nzProteinBaseUri,
      @Named("nzMuscleBaseUri") URI nzMuscleBaseUri) {
    return new ProductsFactoryImpl(chemistWarehouseBaseUri, nzProteinBaseUri, nzMuscleBaseUri);
  }
}
