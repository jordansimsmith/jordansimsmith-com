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
  DynamoDbTable<AuctionTrackerItem> auctionTrackerTable(
      DynamoDbEnhancedClient dynamoDbEnhancedClient) {
    var schema = TableSchema.fromBean(AuctionTrackerItem.class);
    return dynamoDbEnhancedClient.table("auction_tracker", schema);
  }

  @Provides
  @Singleton
  FakeSearchFactory fakeSearchFactory() {
    return new FakeSearchFactory();
  }

  @Provides
  @Singleton
  SearchFactory searchFactory(FakeSearchFactory fakeSearchFactory) {
    return fakeSearchFactory;
  }

  @Provides
  @Singleton
  FakeTradeMeClient fakeTradeMeClient() {
    return new FakeTradeMeClient();
  }

  @Provides
  @Singleton
  TradeMeClient tradeMeClient(FakeTradeMeClient fakeTradeMeClient) {
    return fakeTradeMeClient;
  }
}
