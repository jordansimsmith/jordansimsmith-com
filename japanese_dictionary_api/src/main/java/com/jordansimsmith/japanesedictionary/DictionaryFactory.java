package com.jordansimsmith.japanesedictionary;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jordansimsmith.dynamodb.DynamoDbModule;
import com.jordansimsmith.http.HttpResponseFactory;
import com.jordansimsmith.http.RequestContextFactory;
import com.jordansimsmith.http.RequestContextModule;
import com.jordansimsmith.json.ObjectMapperModule;
import com.jordansimsmith.secrets.Secrets;
import com.jordansimsmith.secrets.SecretsModule;
import dagger.Component;
import javax.inject.Singleton;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

@Singleton
@Component(
    modules = {
      ObjectMapperModule.class,
      SecretsModule.class,
      DynamoDbModule.class,
      RequestContextModule.class,
      DictionaryModule.class
    })
public interface DictionaryFactory {
  ObjectMapper objectMapper();

  Secrets secrets();

  RequestContextFactory requestContextFactory();

  HttpResponseFactory httpResponseFactory();

  DynamoDbClient dynamoDbClient();

  DynamoDbEnhancedClient dynamoDbEnhancedClient();

  DynamoDbTable<JapaneseDictionaryItem> japaneseDictionaryTable();

  static DictionaryFactory create() {
    return DaggerDictionaryFactory.create();
  }
}
