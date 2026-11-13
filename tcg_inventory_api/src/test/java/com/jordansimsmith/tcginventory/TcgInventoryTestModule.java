package com.jordansimsmith.tcginventory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jordansimsmith.http.HttpResponseFactory;
import com.jordansimsmith.queue.FakeQueueClient;
import com.jordansimsmith.queue.QueueClient;
import com.jordansimsmith.tcginventory.catalog.CatalogSnapshotItem;
import com.jordansimsmith.tcginventory.catalog.CatalogSnapshotStore;
import com.jordansimsmith.tcginventory.catalog.Catalogs;
import com.jordansimsmith.tcginventory.catalog.FakeCardCatalogs;
import com.jordansimsmith.tcginventory.catalog.FakeTcgCsvClient;
import com.jordansimsmith.tcginventory.catalog.TcgCsvClient;
import com.jordansimsmith.tcginventory.catalog.server.CatalogModule;
import com.jordansimsmith.tcginventory.fetchtcg.client.FakeFetchTcgService;
import com.jordansimsmith.tcginventory.fetchtcg.client.FetchTcgService;
import com.jordansimsmith.tcginventory.games.client.FakeGamesService;
import com.jordansimsmith.tcginventory.games.client.GamesService;
import com.jordansimsmith.tcginventory.imports.server.ImportMessage;
import com.jordansimsmith.tcginventory.imports.server.ImportsModule;
import com.jordansimsmith.tcginventory.inventory.client.FakeInventoryService;
import com.jordansimsmith.tcginventory.inventory.client.InventoryService;
import com.jordansimsmith.tcginventory.listings.server.ListingsModule;
import com.jordansimsmith.tcginventory.orders.server.FulfillmentMessage;
import com.jordansimsmith.tcginventory.orders.server.OrdersModule;
import com.jordansimsmith.tcginventory.photos.client.FakePhotosService;
import com.jordansimsmith.tcginventory.photos.client.PhotosService;
import com.jordansimsmith.tcginventory.pricing.server.PricingModule;
import com.jordansimsmith.tcginventory.pricing.server.RepricingMessage;
import com.jordansimsmith.tcginventory.publish.server.PublishMessage;
import com.jordansimsmith.tcginventory.publish.server.PublishModule;
import com.jordansimsmith.tcginventory.reports.server.ReportsModule;
import com.jordansimsmith.tcginventory.scans.ScanItem;
import com.jordansimsmith.tcginventory.scans.ScanRepository;
import com.jordansimsmith.tcginventory.scans.ScanRowItem;
import com.jordansimsmith.tcginventory.scans.server.ScanImages;
import com.jordansimsmith.tcginventory.scans.server.ScansModule;
import com.jordansimsmith.time.Clock;
import dagger.Module;
import dagger.Provides;
import java.net.URI;
import javax.inject.Named;
import javax.inject.Singleton;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.sqs.SqsClient;

@Module(
    includes = {
      PricingModule.class,
      OrdersModule.class,
      CatalogModule.class,
      ListingsModule.class,
      ReportsModule.class,
      PublishModule.class,
      ImportsModule.class,
      ScansModule.class
    })
public class TcgInventoryTestModule {
  @Provides
  @Singleton
  @Named("tcgInventoryTableName")
  String tcgInventoryTableName() {
    return TcgInventoryTable.TABLE_NAME;
  }

  @Provides
  @Singleton
  @Named("catalogSnapshotBucket")
  String catalogSnapshotBucket() {
    return CatalogSnapshotStore.BUCKET;
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
    return URI.create("http://localhost:1");
  }

  @Provides
  @Singleton
  @Named("reportsQueueName")
  String reportsQueueName() {
    return "tcg_inventory_reports.fifo";
  }

  @Provides
  @Singleton
  @Named("publishQueueName")
  String publishQueueName() {
    return "tcg_inventory_publish.fifo";
  }

  @Provides
  @Singleton
  @Named("photosBucket")
  String photosBucket() {
    return "api.tcg-inventory.jordansimsmith.com";
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
  DynamoDbTable<TcgInventoryTableDefinition> tableDefinition(
      DynamoDbEnhancedClient dynamoDbEnhancedClient) {
    var schema = TableSchema.fromBean(TcgInventoryTableDefinition.class);
    return dynamoDbEnhancedClient.table(TcgInventoryTable.TABLE_NAME, schema);
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
  ScanRepository scanRepository(
      DynamoDbTable<ScanItem> scanTable,
      DynamoDbTable<ScanRowItem> scanRowTable,
      DynamoDbClient dynamoDbClient,
      Clock clock) {
    return new ScanRepository(
        scanTable, scanRowTable, dynamoDbClient, clock, TcgInventoryTable.TABLE_NAME);
  }

  @Provides
  @Singleton
  DynamoDbTable<JobItem> jobTable(DynamoDbEnhancedClient client) {
    return client.table(TcgInventoryTable.TABLE_NAME, TableSchema.fromBean(JobItem.class));
  }

  @Provides
  @Singleton
  DynamoDbTable<CatalogSnapshotItem> catalogSnapshotTable(DynamoDbEnhancedClient client) {
    return client.table(
        TcgInventoryTable.TABLE_NAME, TableSchema.fromBean(CatalogSnapshotItem.class));
  }

  @Provides
  @Singleton
  SqsClient sqsClient() {
    return SqsClient.builder().region(Region.of("ap-southeast-2")).build();
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
  FakeQueueClient<RepricingMessage> fakePricingQueue() {
    return new FakeQueueClient<>();
  }

  @Provides
  @Singleton
  QueueClient<RepricingMessage> pricingQueue(FakeQueueClient<RepricingMessage> fakePricingQueue) {
    return fakePricingQueue;
  }

  @Provides
  @Singleton
  FakeQueueClient<PublishMessage> fakePublishQueue() {
    return new FakeQueueClient<>();
  }

  @Provides
  @Singleton
  QueueClient<PublishMessage> publishQueue(FakeQueueClient<PublishMessage> fakePublishQueue) {
    return fakePublishQueue;
  }

  @Provides
  @Singleton
  FakeQueueClient<FulfillmentMessage> fakeOrdersQueue() {
    return new FakeQueueClient<>();
  }

  @Provides
  @Singleton
  QueueClient<FulfillmentMessage> ordersQueue(FakeQueueClient<FulfillmentMessage> fakeOrdersQueue) {
    return fakeOrdersQueue;
  }

  @Provides
  @Singleton
  FakeQueueClient<ImportMessage> fakeImportsQueue() {
    return new FakeQueueClient<>();
  }

  @Provides
  @Singleton
  QueueClient<ImportMessage> importsQueue(FakeQueueClient<ImportMessage> fakeImportsQueue) {
    return fakeImportsQueue;
  }

  @Provides
  @Singleton
  FakeInventoryService fakeInventoryService(GamesService gamesService) {
    return new FakeInventoryService(gamesService);
  }

  @Provides
  @Singleton
  InventoryService inventoryService(FakeInventoryService fakeInventoryService) {
    return fakeInventoryService;
  }

  @Provides
  @Singleton
  FakeFetchTcgService fakeFetchTcgService() {
    return new FakeFetchTcgService();
  }

  @Provides
  @Singleton
  FetchTcgService fetchTcgService(FakeFetchTcgService fakeFetchTcgService) {
    return fakeFetchTcgService;
  }

  @Provides
  @Singleton
  FakePhotosService fakePhotosService() {
    return new FakePhotosService();
  }

  @Provides
  @Singleton
  PhotosService photosService(FakePhotosService fakePhotosService) {
    return fakePhotosService;
  }

  @Provides
  @Singleton
  GamesService gamesService(FakeGamesService fakeGamesService) {
    return fakeGamesService;
  }

  @Provides
  @Singleton
  FakeGamesService fakeGamesService() {
    return new FakeGamesService();
  }

  @Provides
  @Singleton
  FakeCardCatalogs fakeCardCatalogs() {
    return new FakeCardCatalogs();
  }

  @Provides
  @Singleton
  Catalogs catalogs(FakeCardCatalogs fakeCardCatalogs) {
    return fakeCardCatalogs;
  }

  @Provides
  @Singleton
  FakeTcgCsvClient fakeTcgCsvClient() {
    return new FakeTcgCsvClient();
  }

  @Provides
  @Singleton
  TcgCsvClient tcgCsvClient(FakeTcgCsvClient fakeTcgCsvClient) {
    return fakeTcgCsvClient;
  }
}
