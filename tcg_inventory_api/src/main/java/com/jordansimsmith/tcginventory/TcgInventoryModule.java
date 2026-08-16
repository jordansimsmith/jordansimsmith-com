package com.jordansimsmith.tcginventory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jordansimsmith.http.HttpResponseFactory;
import com.jordansimsmith.queue.QueueClient;
import com.jordansimsmith.queue.SqsQueueClient;
import com.jordansimsmith.secrets.Secrets;
import com.jordansimsmith.time.Clock;
import com.jordansimsmith.ulid.UlidGenerator;
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
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.sqs.SqsClient;

@Module
public class TcgInventoryModule {
  static final String JOBS_QUEUE_NAME = "tcg_inventory_jobs.fifo";

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
  FetchTcgTokenMinter fetchTcgTokenMinter(ObjectMapper objectMapper, Secrets secrets) {
    var firebaseTokenUrl = System.getenv("FIREBASE_TOKEN_URL");
    if (firebaseTokenUrl == null || firebaseTokenUrl.isEmpty()) {
      firebaseTokenUrl =
          "https://securetoken.googleapis.com/v1/token?key=AIzaSyD7SVUprLrgU-bc0Oh756v17y5NKZNQBB8";
    }
    return new HttpFetchTcgTokenMinter(
        URI.create(firebaseTokenUrl), HttpClient.newHttpClient(), objectMapper, secrets);
  }

  @Provides
  @Singleton
  OrderPhaseProcessor orderPhaseProcessor(
      DynamoDbTable<TcgInventoryItem> tcgInventoryTable,
      DynamoDbClient dynamoDbClient,
      Clock clock,
      UlidGenerator ulidGenerator,
      FetchTcgClient fetchTcgClient,
      ObjectMapper objectMapper) {
    return new OrderPhaseProcessor(
        tcgInventoryTable, dynamoDbClient, clock, ulidGenerator, fetchTcgClient, objectMapper);
  }

  @Provides
  @Singleton
  ListingPhaseProcessor listingPhaseProcessor(
      DynamoDbTable<TcgInventoryItem> tcgInventoryTable,
      DynamoDbClient dynamoDbClient,
      Clock clock,
      FetchTcgClient fetchTcgClient) {
    return new ListingPhaseProcessor(tcgInventoryTable, dynamoDbClient, clock, fetchTcgClient);
  }

  @Provides
  @Singleton
  PublishJobProcessor publishJobProcessor(
      FetchTcgTokenMinter fetchTcgTokenMinter,
      OrderPhaseProcessor orderPhaseProcessor,
      ListingPhaseProcessor listingPhaseProcessor) {
    return new PublishJobProcessor(fetchTcgTokenMinter, orderPhaseProcessor, listingPhaseProcessor);
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
    var fetchTcgBaseUrl = System.getenv("FETCHTCG_BASE_URL");
    if (fetchTcgBaseUrl == null || fetchTcgBaseUrl.isEmpty()) {
      fetchTcgBaseUrl = "https://api.fetchtcg.com";
    }
    return new HttpFetchTcgClient(
        URI.create(fetchTcgBaseUrl), HttpClient.newHttpClient(), objectMapper, pacer);
  }
}
