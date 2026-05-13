package com.jordansimsmith.booktracker;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jordansimsmith.auth.AuthModule;
import com.jordansimsmith.auth.RequestAuthorizer;
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
      SecretsModule.class,
      ClockModule.class,
      DynamoDbModule.class,
      RequestContextModule.class,
      AuthModule.class,
      BookTrackerModule.class
    })
public interface BookTrackerFactory {
  ObjectMapper objectMapper();

  Secrets secrets();

  Clock clock();

  RequestContextFactory requestContextFactory();

  HttpResponseFactory httpResponseFactory();

  RequestAuthorizer requestAuthorizer();

  DynamoDbTable<BookTrackerItem> bookTrackerTable();

  BookValidator bookValidator();

  static BookTrackerFactory create() {
    return DaggerBookTrackerFactory.create();
  }
}
