package com.jordansimsmith.tcginventory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jordansimsmith.dynamodb.DynamoDbModule;
import com.jordansimsmith.http.HttpResponseFactory;
import com.jordansimsmith.http.RequestContextModule;
import com.jordansimsmith.json.ObjectMapperModule;
import com.jordansimsmith.queue.QueueClient;
import com.jordansimsmith.queue.SqsQueueClient;
import com.jordansimsmith.s3.S3Module;
import com.jordansimsmith.secrets.SecretsModule;
import com.jordansimsmith.tcginventory.fetchtcg.FetchTcgModule;
import com.jordansimsmith.time.ClockModule;
import com.jordansimsmith.ulid.UlidModule;
import dagger.Module;
import dagger.Provides;
import javax.inject.Singleton;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.http.crt.AwsCrtHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;

@Module(
    includes = {
      ObjectMapperModule.class,
      ClockModule.class,
      DynamoDbModule.class,
      SecretsModule.class,
      RequestContextModule.class,
      UlidModule.class,
      S3Module.class,
      FetchTcgModule.class
    })
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
