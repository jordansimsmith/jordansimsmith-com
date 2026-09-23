package com.jordansimsmith.tcginventory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jordansimsmith.http.HttpResponseFactory;
import com.jordansimsmith.queue.FakeQueueClient;
import com.jordansimsmith.queue.QueueClient;
import com.jordansimsmith.time.Clock;
import com.jordansimsmith.ulid.UlidGenerator;
import dagger.Module;
import dagger.Provides;
import javax.inject.Singleton;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

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
