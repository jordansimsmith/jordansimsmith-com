package com.jordansimsmith.tcginventory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jordansimsmith.dynamodb.DynamoDbModule;
import com.jordansimsmith.http.HttpResponseFactory;
import com.jordansimsmith.http.RequestContextFactory;
import com.jordansimsmith.http.RequestContextModule;
import com.jordansimsmith.json.ObjectMapperModule;
import com.jordansimsmith.queue.QueueClient;
import com.jordansimsmith.secrets.Secrets;
import com.jordansimsmith.secrets.SecretsModule;
import com.jordansimsmith.time.Clock;
import com.jordansimsmith.time.ClockModule;
import com.jordansimsmith.ulid.UlidGenerator;
import com.jordansimsmith.ulid.UlidModule;
import dagger.Component;
import javax.inject.Singleton;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

@Singleton
@Component(
    modules = {
      ObjectMapperModule.class,
      ClockModule.class,
      DynamoDbModule.class,
      SecretsModule.class,
      RequestContextModule.class,
      UlidModule.class,
      TcgInventoryModule.class
    })
public interface TcgInventoryFactory {
  ObjectMapper objectMapper();

  Clock clock();

  RequestContextFactory requestContextFactory();

  HttpResponseFactory httpResponseFactory();

  Secrets secrets();

  DynamoDbTable<TcgInventoryItem> tcgInventoryTable();

  DynamoDbClient dynamoDbClient();

  QueueClient<JobMessage> jobsQueue();

  UlidGenerator ulidGenerator();

  FetchTcgClient fetchTcgClient();

  FetchTcgTokenMinter fetchTcgTokenMinter();

  AppraiseJobProcessor appraiseJobProcessor();

  PublishJobProcessor publishJobProcessor();

  static TcgInventoryFactory create() {
    return DaggerTcgInventoryFactory.create();
  }
}
