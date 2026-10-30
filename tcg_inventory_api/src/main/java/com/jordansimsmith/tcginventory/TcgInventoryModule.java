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
import com.jordansimsmith.tcginventory.catalog.server.CatalogModule;
import com.jordansimsmith.tcginventory.fetchtcg.server.FetchTcgModule;
import com.jordansimsmith.tcginventory.games.server.GamesModule;
import com.jordansimsmith.tcginventory.pricing.server.PricingModule;
import com.jordansimsmith.time.ClockModule;
import com.jordansimsmith.ulid.UlidModule;
import dagger.Module;
import dagger.Provides;
import java.net.URI;
import javax.inject.Named;
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
      FetchTcgModule.class,
      GamesModule.class,
      CatalogModule.class,
      PricingModule.class
    })
public class TcgInventoryModule {
  static final String JOBS_QUEUE_NAME = "tcg_inventory_jobs.fifo";

  @Provides
  @Singleton
  @Named("tcgInventoryTableName")
  String tcgInventoryTableName() {
    return TcgInventoryTable.TABLE_NAME;
  }

  @Provides
  @Singleton
  @Named("firebaseTokenUri")
  URI firebaseTokenUri() {
    var firebaseTokenUrl = System.getenv("FIREBASE_TOKEN_URL");
    if (firebaseTokenUrl == null || firebaseTokenUrl.isEmpty()) {
      firebaseTokenUrl =
          "https://securetoken.googleapis.com/v1/token?key=AIzaSyD7SVUprLrgU-bc0Oh756v17y5NKZNQBB8";
    }
    return URI.create(firebaseTokenUrl);
  }

  @Provides
  @Singleton
  @Named("fetchTcgBaseUri")
  URI fetchTcgBaseUri() {
    var fetchTcgBaseUrl = System.getenv("FETCHTCG_BASE_URL");
    if (fetchTcgBaseUrl == null || fetchTcgBaseUrl.isEmpty()) {
      fetchTcgBaseUrl = "https://api.fetchtcg.com";
    }
    return URI.create(fetchTcgBaseUrl);
  }

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
