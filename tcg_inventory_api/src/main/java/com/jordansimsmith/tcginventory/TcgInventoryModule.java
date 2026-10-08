package com.jordansimsmith.tcginventory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jordansimsmith.http.HttpResponseFactory;
import com.jordansimsmith.queue.QueueClient;
import com.jordansimsmith.queue.SqsQueueClient;
import com.jordansimsmith.tcginventory.catalog.CatalogRepository;
import com.jordansimsmith.tcginventory.catalog.CatalogSnapshotItem;
import com.jordansimsmith.tcginventory.catalog.CatalogSnapshotStore;
import com.jordansimsmith.tcginventory.catalog.TcgCsvCatalogArtifactCodec;
import dagger.Module;
import dagger.Provides;
import javax.inject.Singleton;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.http.crt.AwsCrtHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.s3.S3Client;
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
  DynamoDbTable<JobItem> jobTable(DynamoDbEnhancedClient client) {
    return client.table(TcgInventoryTable.TABLE_NAME, TableSchema.fromBean(JobItem.class));
  }

  @Provides
  @Singleton
  DynamoDbTable<AuditItem> auditTable(DynamoDbEnhancedClient client) {
    return client.table(TcgInventoryTable.TABLE_NAME, TableSchema.fromBean(AuditItem.class));
  }

  @Provides
  @Singleton
  DynamoDbTable<CatalogSnapshotItem> catalogSnapshotTable(DynamoDbEnhancedClient client) {
    return client.table(
        TcgInventoryTable.TABLE_NAME, TableSchema.fromBean(CatalogSnapshotItem.class));
  }

  @Provides
  @Singleton
  CatalogRepository catalogRepository(
      DynamoDbTable<CatalogSnapshotItem> catalogSnapshotTable, DynamoDbClient dynamoDbClient) {
    return new CatalogRepository(catalogSnapshotTable, dynamoDbClient);
  }

  @Provides
  @Singleton
  CatalogSnapshotStore catalogSnapshotStore(
      S3Client s3Client, CatalogRepository catalogRepository, ObjectMapper objectMapper) {
    return new CatalogSnapshotStore(
        s3Client, Photos.BUCKET, catalogRepository, new TcgCsvCatalogArtifactCodec(objectMapper));
  }

  @Provides
  @Singleton
  SqsClient sqsClient() {
    var sqsClient =
        SqsClient.builder()
            .region(Region.of(System.getenv("AWS_REGION")))
            .httpClientBuilder(AwsCrtHttpClient.builder())
            .build();
    return sqsClient;
  }

  @Provides
  @Singleton
  QueueClient<JobMessage> jobsQueue(ObjectMapper objectMapper, SqsClient sqsClient) {
    return SqsQueueClient.create(sqsClient, objectMapper, JOBS_QUEUE_NAME);
  }
}
