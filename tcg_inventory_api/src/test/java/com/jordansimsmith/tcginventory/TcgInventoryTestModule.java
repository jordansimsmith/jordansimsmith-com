package com.jordansimsmith.tcginventory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jordansimsmith.http.HttpResponseFactory;
import com.jordansimsmith.queue.FakeQueueClient;
import com.jordansimsmith.queue.QueueClient;
import dagger.Module;
import dagger.Provides;
import javax.inject.Singleton;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;

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
  FakeQueueClient<JobMessage> fakeJobsQueue() {
    return new FakeQueueClient<>();
  }

  @Provides
  @Singleton
  QueueClient<JobMessage> jobsQueue(FakeQueueClient<JobMessage> fakeJobsQueue) {
    return fakeJobsQueue;
  }
}
