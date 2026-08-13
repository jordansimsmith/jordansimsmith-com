package com.jordansimsmith.tcginventory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jordansimsmith.http.HttpResponseFactory;
import com.jordansimsmith.queue.QueueClient;
import com.jordansimsmith.queue.SqsQueueClient;
import com.jordansimsmith.time.Clock;
import dagger.Module;
import dagger.Provides;
import java.net.URI;
import java.net.http.HttpClient;
import java.util.concurrent.ThreadLocalRandom;
import javax.inject.Singleton;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.http.crt.AwsCrtHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;

@Module
public class TcgInventoryModule {
  static final String JOBS_QUEUE_NAME = "tcg_inventory_jobs";

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
  QueueClient<JobMessage> jobsQueue(ObjectMapper objectMapper) {
    var sqsClient =
        SqsClient.builder()
            .region(Region.of(System.getenv("AWS_REGION")))
            .httpClientBuilder(AwsCrtHttpClient.builder())
            .build();
    return SqsQueueClient.create(sqsClient, objectMapper, JOBS_QUEUE_NAME);
  }

  @Provides
  @Singleton
  AppraiseJobProcessor appraiseJobProcessor(
      DynamoDbTable<TcgInventoryItem> tcgInventoryTable,
      Clock clock,
      FetchTcgClient fetchTcgClient) {
    return new AppraiseJobProcessor(tcgInventoryTable, clock, fetchTcgClient);
  }

  @Provides
  @Singleton
  PublishJobProcessor publishJobProcessor() {
    return new PublishJobProcessor();
  }

  @Provides
  @Singleton
  FetchTcgClient fetchTcgClient(ObjectMapper objectMapper) {
    Runnable pacer =
        () -> {
          try {
            Thread.sleep(ThreadLocalRandom.current().nextLong(1000, 2000));
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
          }
        };
    return new HttpFetchTcgClient(
        URI.create("https://api.fetchtcg.com"), HttpClient.newHttpClient(), objectMapper, pacer);
  }
}
