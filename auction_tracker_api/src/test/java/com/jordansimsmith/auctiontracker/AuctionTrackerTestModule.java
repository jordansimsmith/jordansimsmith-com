package com.jordansimsmith.auctiontracker;

import dagger.Module;
import dagger.Provides;
import javax.inject.Singleton;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;

@Module
public class AuctionTrackerTestModule {
  @Provides
  @Singleton
  public DynamoDbTable<AuctionTrackerItem> auctionTrackerTable(
      DynamoDbEnhancedClient dynamoDbEnhancedClient) {
    var schema = TableSchema.fromBean(AuctionTrackerItem.class);
    return dynamoDbEnhancedClient.table("auction_tracker", schema);
  }

  @Provides
  @Singleton
  public FakeSearchFactory fakeSearchFactory() {
    return new FakeSearchFactory();
  }

  @Provides
  @Singleton
  public SearchFactory searchFactory(FakeSearchFactory fakeSearchFactory) {
    return fakeSearchFactory;
  }

  @Provides
  @Singleton
  public FakeTradeMeClient fakeTradeMeClient() {
    return new FakeTradeMeClient();
  }

  @Provides
  @Singleton
  public TradeMeClient tradeMeClient(FakeTradeMeClient fakeTradeMeClient) {
    return fakeTradeMeClient;
  }
}
