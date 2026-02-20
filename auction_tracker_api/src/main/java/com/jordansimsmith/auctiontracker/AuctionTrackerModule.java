package com.jordansimsmith.auctiontracker;

import dagger.Module;
import dagger.Provides;
import java.net.URI;
import javax.inject.Singleton;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;

@Module
public class AuctionTrackerModule {
  @Provides
  @Singleton
  public DynamoDbTable<AuctionTrackerItem> auctionTrackerTable(
      DynamoDbEnhancedClient dynamoDbEnhancedClient) {
    var schema = TableSchema.fromBean(AuctionTrackerItem.class);
    return dynamoDbEnhancedClient.table("auction_tracker", schema);
  }

  @Provides
  @Singleton
  public SearchFactory searchFactory() {
    var baseUrl = System.getenv("AUCTION_TRACKER_TRADEME_BASE_URL");
    if (baseUrl == null || baseUrl.isBlank()) {
      baseUrl = "https://www.trademe.co.nz";
    }
    return new SearchFactoryImpl(URI.create(baseUrl));
  }

  @Provides
  @Singleton
  public TradeMeClient tradeMeClient() {
    return new JsoupTradeMeClient();
  }
}
