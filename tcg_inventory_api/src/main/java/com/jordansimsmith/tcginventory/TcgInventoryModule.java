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
  static final String SCAN_QUEUE_NAME = "tcg_inventory_scan_jobs";

  @Provides
  @Singleton
  HttpResponseFactory httpResponseFactory(ObjectMapper objectMapper) {
    return new HttpResponseFactory.Builder(objectMapper)
        .withAllowedOrigin("https://tcg-inventory.jordansimsmith.com")
        .build();
  }

  @Provides
  @Singleton
  DynamoDbTable<SkuItem> skuTable(DynamoDbEnhancedClient client) {
    return client.table(TcgInventoryTable.TABLE_NAME, TableSchema.fromBean(SkuItem.class));
  }

  @Provides
  @Singleton
  DynamoDbTable<UnitItem> unitTable(DynamoDbEnhancedClient client) {
    return client.table(TcgInventoryTable.TABLE_NAME, TableSchema.fromBean(UnitItem.class));
  }

  @Provides
  @Singleton
  DynamoDbTable<ImportItem> importTable(DynamoDbEnhancedClient client) {
    return client.table(TcgInventoryTable.TABLE_NAME, TableSchema.fromBean(ImportItem.class));
  }

  @Provides
  @Singleton
  DynamoDbTable<ImportRowItem> importRowTable(DynamoDbEnhancedClient client) {
    return client.table(TcgInventoryTable.TABLE_NAME, TableSchema.fromBean(ImportRowItem.class));
  }

  @Provides
  @Singleton
  DynamoDbTable<ScanItem> scanTable(DynamoDbEnhancedClient client) {
    return client.table(TcgInventoryTable.TABLE_NAME, TableSchema.fromBean(ScanItem.class));
  }

  @Provides
  @Singleton
  DynamoDbTable<ScanRowItem> scanRowTable(DynamoDbEnhancedClient client) {
    return client.table(TcgInventoryTable.TABLE_NAME, TableSchema.fromBean(ScanRowItem.class));
  }

  @Provides
  @Singleton
  DynamoDbTable<OrderItem> orderTable(DynamoDbEnhancedClient client) {
    return client.table(TcgInventoryTable.TABLE_NAME, TableSchema.fromBean(OrderItem.class));
  }

  @Provides
  @Singleton
  DynamoDbTable<JobItem> jobTable(DynamoDbEnhancedClient client) {
    return client.table(TcgInventoryTable.TABLE_NAME, TableSchema.fromBean(JobItem.class));
  }

  @Provides
  @Singleton
  DynamoDbTable<SettingsItem> settingsTable(DynamoDbEnhancedClient client) {
    return client.table(TcgInventoryTable.TABLE_NAME, TableSchema.fromBean(SettingsItem.class));
  }

  @Provides
  @Singleton
  DynamoDbTable<ReportItem> reportTable(DynamoDbEnhancedClient client) {
    return client.table(TcgInventoryTable.TABLE_NAME, TableSchema.fromBean(ReportItem.class));
  }

  @Provides
  @Singleton
  DynamoDbTable<AuditItem> auditTable(DynamoDbEnhancedClient client) {
    return client.table(TcgInventoryTable.TABLE_NAME, TableSchema.fromBean(AuditItem.class));
  }

  @Provides
  @Singleton
  DynamoDbTable<SequenceCounterItem> sequenceCounterTable(DynamoDbEnhancedClient client) {
    return client.table(
        TcgInventoryTable.TABLE_NAME, TableSchema.fromBean(SequenceCounterItem.class));
  }

  @Provides
  @Singleton
  TcgInventoryRepository tcgInventoryRepository(
      DynamoDbTable<UnitItem> unitTable,
      DynamoDbTable<ScanItem> scanTable,
      DynamoDbTable<ScanRowItem> scanRowTable,
      DynamoDbTable<OrderItem> orderTable,
      DynamoDbClient dynamoDbClient,
      Clock clock,
      UlidGenerator ulidGenerator) {
    return new TcgInventoryRepository(
        unitTable, scanTable, scanRowTable, orderTable, dynamoDbClient, clock, ulidGenerator);
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
  QueueClient<ScanMessage> scanQueue(ObjectMapper objectMapper) {
    var sqsClient =
        SqsClient.builder()
            .region(Region.of(System.getenv("AWS_REGION")))
            .httpClientBuilder(AwsCrtHttpClient.builder())
            .build();
    return SqsQueueClient.create(sqsClient, objectMapper, SCAN_QUEUE_NAME);
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
