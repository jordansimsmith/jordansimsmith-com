package com.jordansimsmith.tcginventory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jordansimsmith.dynamodb.DynamoDbModule;
import com.jordansimsmith.http.HttpResponseFactory;
import com.jordansimsmith.http.RequestContextFactory;
import com.jordansimsmith.http.RequestContextModule;
import com.jordansimsmith.json.ObjectMapperModule;
import com.jordansimsmith.secrets.Secrets;
import com.jordansimsmith.secrets.SecretsModule;
import com.jordansimsmith.time.Clock;
import com.jordansimsmith.time.ClockModule;
import dagger.Component;
import javax.inject.Singleton;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;

@Singleton
@Component(
    modules = {
      ObjectMapperModule.class,
      ClockModule.class,
      DynamoDbModule.class,
      SecretsModule.class,
      RequestContextModule.class,
      TcgInventoryModule.class
    })
public interface TcgInventoryFactory {
  ObjectMapper objectMapper();

  Clock clock();

  RequestContextFactory requestContextFactory();

  HttpResponseFactory httpResponseFactory();

  Secrets secrets();

  DynamoDbTable<TcgInventoryItem> tcgInventoryTable();

  static TcgInventoryFactory create() {
    return DaggerTcgInventoryFactory.create();
  }
}
