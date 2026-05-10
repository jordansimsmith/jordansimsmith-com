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
public class DictionaryModule {
  @Provides
  @Singleton
  public HttpResponseFactory httpResponseFactory(ObjectMapper objectMapper) {
    return new HttpResponseFactory.Builder(objectMapper)
        .withAllowedOrigin("https://japanese-dictionary.jordansimsmith.com")
        .build();
  }

  @Provides
  @Singleton
  public DynamoDbTable<JapaneseDictionaryItem> japaneseDictionaryTable(
      DynamoDbEnhancedClient dynamoDbEnhancedClient) {
    var schema = TableSchema.fromBean(JapaneseDictionaryItem.class);
    return dynamoDbEnhancedClient.table(JapaneseDictionaryItem.TABLE_NAME, schema);
  }
}
