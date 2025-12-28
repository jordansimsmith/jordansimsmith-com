package com.jordansimsmith.packinglist;

import dagger.Module;
import dagger.Provides;
import javax.inject.Singleton;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;

@Module
public class PackingListModule {
  @Provides
  @Singleton
  public TemplatesFactory templatesFactory() {
    return new TemplatesFactoryImpl();
  }

  @Provides
  @Singleton
  public DynamoDbTable<PackingListItem> packingListTable(
      DynamoDbEnhancedClient dynamoDbEnhancedClient) {
    var schema = TableSchema.fromBean(PackingListItem.class);
    return dynamoDbEnhancedClient.table(PackingListItem.TABLE_NAME, schema);
  }
}
