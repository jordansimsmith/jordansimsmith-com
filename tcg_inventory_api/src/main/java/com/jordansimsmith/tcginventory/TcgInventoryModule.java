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
import com.jordansimsmith.tcginventory.imports.server.ImportMessage;
import com.jordansimsmith.tcginventory.imports.server.ImportsModule;
import com.jordansimsmith.tcginventory.inventory.server.InventoryModule;
import com.jordansimsmith.tcginventory.listings.server.ListingsModule;
import com.jordansimsmith.tcginventory.orders.server.FulfillmentMessage;
import com.jordansimsmith.tcginventory.orders.server.OrdersModule;
import com.jordansimsmith.tcginventory.photos.server.PhotosModule;
import com.jordansimsmith.tcginventory.pricing.server.PricingModule;
import com.jordansimsmith.tcginventory.pricing.server.RepricingMessage;
import com.jordansimsmith.tcginventory.publish.server.PublishMessage;
import com.jordansimsmith.tcginventory.publish.server.PublishModule;
import com.jordansimsmith.tcginventory.reports.server.ReportsModule;
import com.jordansimsmith.tcginventory.scans.server.ScanImages;
import com.jordansimsmith.tcginventory.scans.server.ScansModule;
import com.jordansimsmith.time.ClockModule;
import com.jordansimsmith.ulid.UlidModule;
import dagger.Module;
import dagger.Provides;
import java.net.URI;
import javax.inject.Named;
import javax.inject.Singleton;
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
      InventoryModule.class,
      ImportsModule.class,
      ListingsModule.class,
      CatalogModule.class,
      ScansModule.class,
      PricingModule.class,
      PhotosModule.class,
      OrdersModule.class,
      ReportsModule.class,
      PublishModule.class
    })
public class TcgInventoryModule {
  static final String REPORTS_QUEUE_NAME = "tcg_inventory_reports.fifo";

  static final String PRICING_QUEUE_NAME = "tcg_inventory_pricing.fifo";

  static final String PUBLISH_QUEUE_NAME = "tcg_inventory_publish.fifo";

  static final String ORDERS_QUEUE_NAME = "tcg_inventory_orders.fifo";

  static final String IMPORTS_QUEUE_NAME = "tcg_inventory_imports.fifo";

  @Provides
  @Singleton
  @Named("tcgInventoryTableName")
  String tcgInventoryTableName() {
    return TcgInventoryTable.TABLE_NAME;
  }

  @Provides
  @Singleton
  @Named("reportsQueueName")
  String reportsQueueName() {
    return REPORTS_QUEUE_NAME;
  }

  @Provides
  @Singleton
  @Named("pricingQueueName")
  String pricingQueueName() {
    return PRICING_QUEUE_NAME;
  }

  @Provides
  @Singleton
  @Named("publishQueueName")
  String publishQueueName() {
    return PUBLISH_QUEUE_NAME;
  }

  @Provides
  @Singleton
  @Named("ordersQueueName")
  String ordersQueueName() {
    return ORDERS_QUEUE_NAME;
  }

  @Provides
  @Singleton
  @Named("importsQueueName")
  String importsQueueName() {
    return IMPORTS_QUEUE_NAME;
  }

  @Provides
  @Singleton
  @Named("photosBucket")
  String photosBucket() {
    return "api.tcg-inventory.jordansimsmith.com";
  }

  @Provides
  @Singleton
  @Named("catalogSnapshotBucket")
  String catalogSnapshotBucket() {
    return "api.tcg-inventory.jordansimsmith.com";
  }

  @Provides
  @Singleton
  @Named("scanImagesBucket")
  String scanImagesBucket() {
    return ScanImages.BUCKET;
  }

  @Provides
  @Singleton
  @Named("scryfallBaseUri")
  URI scryfallBaseUri() {
    var baseUrl = System.getenv("SCRYFALL_BASE_URL");
    return URI.create(baseUrl == null || baseUrl.isBlank() ? "https://api.scryfall.com" : baseUrl);
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
  QueueClient<RepricingMessage> pricingQueue(
      ObjectMapper objectMapper, SqsClient sqsClient, @Named("pricingQueueName") String queueName) {
    return SqsQueueClient.create(sqsClient, objectMapper, queueName);
  }

  @Provides
  @Singleton
  QueueClient<PublishMessage> publishQueue(
      ObjectMapper objectMapper, SqsClient sqsClient, @Named("publishQueueName") String queueName) {
    return SqsQueueClient.create(sqsClient, objectMapper, queueName);
  }

  @Provides
  @Singleton
  QueueClient<FulfillmentMessage> ordersQueue(
      ObjectMapper objectMapper, SqsClient sqsClient, @Named("ordersQueueName") String queueName) {
    return SqsQueueClient.create(sqsClient, objectMapper, queueName);
  }

  @Provides
  @Singleton
  QueueClient<ImportMessage> importsQueue(
      ObjectMapper objectMapper, SqsClient sqsClient, @Named("importsQueueName") String queueName) {
    return SqsQueueClient.create(sqsClient, objectMapper, queueName);
  }
}
