package com.jordansimsmith.tcginventory;

import com.jordansimsmith.dynamodb.DynamoDbTestModule;
import com.jordansimsmith.http.RequestContextModule;
import com.jordansimsmith.json.ObjectMapperModule;
import com.jordansimsmith.queue.FakeQueueClient;
import com.jordansimsmith.s3.S3TestModule;
import com.jordansimsmith.secrets.FakeSecrets;
import com.jordansimsmith.secrets.SecretsTestModule;
import com.jordansimsmith.tcginventory.catalog.CatalogFactory;
import com.jordansimsmith.tcginventory.catalog.CatalogSnapshotItem;
import com.jordansimsmith.tcginventory.catalog.FakeCardCatalogs;
import com.jordansimsmith.tcginventory.catalog.FakeTcgCsvClient;
import com.jordansimsmith.tcginventory.fetchtcg.client.FakeFetchTcgService;
import com.jordansimsmith.tcginventory.imports.ImportItem;
import com.jordansimsmith.tcginventory.imports.ImportRowItem;
import com.jordansimsmith.tcginventory.imports.client.ImportService;
import com.jordansimsmith.tcginventory.imports.server.CreateImportOperationItem;
import com.jordansimsmith.tcginventory.imports.server.ImportMessage;
import com.jordansimsmith.tcginventory.inventory.client.FakeInventoryService;
import com.jordansimsmith.tcginventory.inventory.client.InventoryService;
import com.jordansimsmith.tcginventory.listings.client.ListingService;
import com.jordansimsmith.tcginventory.orders.client.OrderService;
import com.jordansimsmith.tcginventory.orders.server.FulfillmentMessage;
import com.jordansimsmith.tcginventory.orders.server.FulfillmentMessageHandler;
import com.jordansimsmith.tcginventory.photos.client.FakePhotosService;
import com.jordansimsmith.tcginventory.photos.client.PhotosService;
import com.jordansimsmith.tcginventory.pricing.client.PricingService;
import com.jordansimsmith.tcginventory.pricing.server.RepricingMessage;
import com.jordansimsmith.tcginventory.pricing.server.RepricingMessageHandler;
import com.jordansimsmith.tcginventory.publish.client.PublishService;
import com.jordansimsmith.tcginventory.publish.server.PublishMessage;
import com.jordansimsmith.tcginventory.publish.server.PublishMessageHandler;
import com.jordansimsmith.tcginventory.reports.client.ReportService;
import com.jordansimsmith.tcginventory.reports.server.ReportMessageHandler;
import com.jordansimsmith.tcginventory.scans.ScanItem;
import com.jordansimsmith.tcginventory.scans.ScanMessage;
import com.jordansimsmith.tcginventory.scans.ScanRepository;
import com.jordansimsmith.tcginventory.scans.ScanRowItem;
import com.jordansimsmith.time.ClockTestModule;
import com.jordansimsmith.time.FakeClock;
import com.jordansimsmith.ulid.FakeUlidGenerator;
import com.jordansimsmith.ulid.UlidTestModule;
import dagger.BindsInstance;
import dagger.Component;
import java.net.URI;
import javax.inject.Named;
import javax.inject.Singleton;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

@Singleton
@Component(
    modules = {
      ObjectMapperModule.class,
      ClockTestModule.class,
      DynamoDbTestModule.class,
      SecretsTestModule.class,
      RequestContextModule.class,
      UlidTestModule.class,
      S3TestModule.class,
      TcgInventoryTestModule.class
    })
public interface TcgInventoryTestFactory extends CatalogFactory {
  URI UNUSED_S3_ENDPOINT = URI.create("http://localhost:1");
  URI UNUSED_FIREBASE_TOKEN_URI = URI.create("http://localhost:1/token");
  URI UNUSED_FETCHTCG_BASE_URI = URI.create("http://localhost:1");

  FakeClock fakeClock();

  FakeSecrets fakeSecrets();

  FakeUlidGenerator fakeUlidGenerator();

  FakeQueueClient<JobMessage> fakeJobsQueue();

  FakeQueueClient<ScanMessage> fakeScanQueue();

  FakeQueueClient<RepricingMessage> fakePricingQueue();

  FakeQueueClient<PublishMessage> fakePublishQueue();

  FakeQueueClient<FulfillmentMessage> fakeOrdersQueue();

  FakeQueueClient<ImportMessage> fakeImportsQueue();

  FakeFetchTcgService fakeFetchTcgService();

  FakePhotosService fakePhotosService();

  FakeCardCatalogs fakeCardCatalogs();

  FakeTcgCsvClient fakeTcgCsvClient();

  DynamoDbClient dynamoDbClient();

  PhotosService photosService();

  DynamoDbTable<TcgInventoryTableDefinition> tableDefinition();

  DynamoDbTable<ImportItem> importTable();

  DynamoDbTable<ImportRowItem> importRowTable();

  DynamoDbTable<CreateImportOperationItem> createImportOperationTable();

  DynamoDbTable<ScanItem> scanTable();

  DynamoDbTable<ScanRowItem> scanRowTable();

  DynamoDbTable<CatalogSnapshotItem> catalogSnapshotTable();

  OrderService orderService();

  FulfillmentMessageHandler fulfillmentMessageHandler();

  ScanRepository scanRepository();

  PricingService pricingService();

  RepricingMessageHandler repricingMessageHandler();

  InventoryService inventoryService();

  ImportService importService();

  FakeInventoryService fakeInventoryService();

  ListingService listingService();

  ReportService reportService();

  ReportMessageHandler reportMessageHandler();

  PublishService publishService();

  PublishMessageHandler publishMessageHandler();

  @Component.Factory
  interface Factory {
    TcgInventoryTestFactory create(
        @BindsInstance @Named("dynamoDbEndpoint") URI dynamoDbEndpoint,
        @BindsInstance @Named("s3Endpoint") URI s3Endpoint,
        @BindsInstance @Named("firebaseTokenUri") URI firebaseTokenUri,
        @BindsInstance @Named("fetchTcgBaseUri") URI fetchTcgBaseUri);
  }

  static TcgInventoryTestFactory create(URI dynamoDbEndpoint, URI s3Endpoint) {
    return DaggerTcgInventoryTestFactory.factory()
        .create(dynamoDbEndpoint, s3Endpoint, UNUSED_FIREBASE_TOKEN_URI, UNUSED_FETCHTCG_BASE_URI);
  }
}
