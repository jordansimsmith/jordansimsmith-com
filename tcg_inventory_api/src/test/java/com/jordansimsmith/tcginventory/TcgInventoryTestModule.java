package com.jordansimsmith.tcginventory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jordansimsmith.http.HttpResponseFactory;
import com.jordansimsmith.queue.FakeQueueClient;
import com.jordansimsmith.queue.QueueClient;
import com.jordansimsmith.time.Clock;
import com.jordansimsmith.ulid.UlidGenerator;
import dagger.Module;
import dagger.Provides;
import javax.inject.Singleton;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

@Module
public class TcgInventoryTestModule {
  @Provides
  @Singleton
  HttpResponseFactory httpResponseFactory(ObjectMapper objectMapper) {
    return new HttpResponseFactory.Builder(objectMapper)
        .withAllowedOrigin("https://tcg-inventory.jordansimsmith.com")
        .build();
  }

  @Provides
  @Singleton
  DynamoDbTable<TcgInventoryItem> tcgInventoryTable(DynamoDbEnhancedClient dynamoDbEnhancedClient) {
    var schema = TableSchema.fromBean(TcgInventoryItem.class);
    return dynamoDbEnhancedClient.table(TcgInventoryItem.TABLE_NAME, schema);
  }

  @Provides
  @Singleton
  TcgInventoryItemRepository tcgInventoryItemRepository(
      DynamoDbTable<TcgInventoryItem> tcgInventoryTable,
      DynamoDbClient dynamoDbClient,
      Clock clock,
      UlidGenerator ulidGenerator) {
    return new TcgInventoryItemRepository(tcgInventoryTable, dynamoDbClient, clock, ulidGenerator);
  }

  @Provides
  @Singleton
  FakeQueueClient<JobMessage> fakeJobsQueue() {
    return new FakeQueueClient<>();
  }

  @Provides
  @Singleton
  QueueClient<JobMessage> jobsQueue(FakeQueueClient<JobMessage> fakeJobsQueue) {
    return fakeJobsQueue;
  }

  @Provides
  @Singleton
  FakeFetchTcgClient fakeFetchTcgClient() {
    return new FakeFetchTcgClient();
  }

  @Provides
  @Singleton
  FetchTcgClient fetchTcgClient(FakeFetchTcgClient fakeFetchTcgClient) {
    return fakeFetchTcgClient;
  }

  @Provides
  @Singleton
  FetchTcgTokenMinter fetchTcgTokenMinter() {
    return new FakeFetchTcgTokenMinter();
  }
}
