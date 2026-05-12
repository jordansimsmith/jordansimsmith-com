package com.jordansimsmith.japanesedictionary;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jordansimsmith.http.HttpResponseFactory;
import dagger.Module;
import dagger.Provides;
import javax.inject.Singleton;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;

@Module
public class JapaneseDictionaryModule {
  private static final String TABLE_NAME = "japanese_dictionary";

  @Provides
  @Singleton
  HttpResponseFactory httpResponseFactory(ObjectMapper objectMapper) {
    return new HttpResponseFactory.Builder(objectMapper)
        .withAllowedOrigin("https://japanese-dictionary.jordansimsmith.com")
        .build();
  }

  @Provides
  @Singleton
  DynamoDbTable<TermItem> termTable(DynamoDbEnhancedClient dynamoDbEnhancedClient) {
    var schema = TableSchema.fromBean(TermItem.class);
    return dynamoDbEnhancedClient.table(TABLE_NAME, schema);
  }

  @Provides
  @Singleton
  DynamoDbTable<BookmarkItem> bookmarkTable(DynamoDbEnhancedClient dynamoDbEnhancedClient) {
    var schema = TableSchema.fromBean(BookmarkItem.class);
    return dynamoDbEnhancedClient.table(TABLE_NAME, schema);
  }

  @Provides
  @Singleton
  RomajiNormaliser romajiNormaliser() {
    return new RomajiNormaliser();
  }
}
