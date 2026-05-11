package com.jordansimsmith.booktracker;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jordansimsmith.http.HttpResponseFactory;
import dagger.Module;
import dagger.Provides;
import javax.inject.Singleton;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;

@Module
public class BookTrackerModule {
  @Provides
  @Singleton
  HttpResponseFactory httpResponseFactory(ObjectMapper objectMapper) {
    return new HttpResponseFactory.Builder(objectMapper)
        .withAllowedOrigin("https://book-tracker.jordansimsmith.com")
        .build();
  }

  @Provides
  @Singleton
  DynamoDbTable<BookTrackerItem> bookTrackerTable(DynamoDbEnhancedClient dynamoDbEnhancedClient) {
    var schema = TableSchema.fromBean(BookTrackerItem.class);
    return dynamoDbEnhancedClient.table(BookTrackerItem.TABLE_NAME, schema);
  }
}
