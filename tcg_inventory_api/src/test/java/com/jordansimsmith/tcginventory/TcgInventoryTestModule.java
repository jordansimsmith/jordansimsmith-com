package com.jordansimsmith.tcginventory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jordansimsmith.http.HttpResponseFactory;
import com.jordansimsmith.queue.FakeQueueClient;
import com.jordansimsmith.queue.QueueClient;
import com.jordansimsmith.tcginventory.fetchtcg.FakeFetchTcgClient;
import com.jordansimsmith.tcginventory.fetchtcg.FakeFetchTcgTokenMinter;
import com.jordansimsmith.tcginventory.fetchtcg.FetchTcgClient;
import com.jordansimsmith.tcginventory.fetchtcg.FetchTcgTokenMinter;
import com.jordansimsmith.tcginventory.imports.ImportItem;
import com.jordansimsmith.tcginventory.imports.ImportRowItem;
import com.jordansimsmith.tcginventory.inventory.SequenceCounterItem;
import com.jordansimsmith.tcginventory.inventory.SkuItem;
import com.jordansimsmith.tcginventory.inventory.UnitItem;
import com.jordansimsmith.tcginventory.orders.OrderItem;
import com.jordansimsmith.tcginventory.reports.ReportItem;
import com.jordansimsmith.tcginventory.scans.ScanItem;
import com.jordansimsmith.tcginventory.scans.ScanMessage;
import com.jordansimsmith.tcginventory.scans.ScanRepository;
import com.jordansimsmith.tcginventory.scans.ScanRowItem;
import com.jordansimsmith.tcginventory.settings.SettingsItem;
import com.jordansimsmith.time.Clock;
import dagger.Module;
import dagger.Provides;
import javax.inject.Singleton;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.sqs.SqsClient;

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
  DynamoDbTable<TcgInventoryTableDefinition> tableDefinition(
      DynamoDbEnhancedClient dynamoDbEnhancedClient) {
    var schema = TableSchema.fromBean(TcgInventoryTableDefinition.class);
    return dynamoDbEnhancedClient.table(TcgInventoryTable.TABLE_NAME, schema);
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
  SqsClient sqsClient() {
    return SqsClient.builder().region(Region.of("ap-southeast-2")).build();
  }

  @Provides
  @Singleton
  ScanRepository scanRepository(
      DynamoDbTable<ScanItem> scanTable,
      DynamoDbTable<ScanRowItem> scanRowTable,
      DynamoDbClient dynamoDbClient,
      Clock clock) {
    return new ScanRepository(scanTable, scanRowTable, dynamoDbClient, clock);
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
  FakeQueueClient<ScanMessage> fakeScanQueue() {
    return new FakeQueueClient<>();
  }

  @Provides
  @Singleton
  QueueClient<ScanMessage> scanQueue(FakeQueueClient<ScanMessage> fakeScanQueue) {
    return fakeScanQueue;
  }

  @Provides
  @Singleton
  FakeFetchTcgClient fakeFetchTcgClient() {
    return new FakeFetchTcgClient();
  }

  @Provides
  @Singleton
  FetchTcgClient fetchTcgClient(FakeFetchTcgClient fakeFetchTcgClient) {
    return fakeFetchTcgClient;
  }

  @Provides
  @Singleton
  FetchTcgTokenMinter fetchTcgTokenMinter() {
    return new FakeFetchTcgTokenMinter();
  }
}
