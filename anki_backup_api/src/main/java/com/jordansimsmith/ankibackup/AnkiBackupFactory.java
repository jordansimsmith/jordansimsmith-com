package com.jordansimsmith.ankibackup;

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
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

@Singleton
@Component(
    modules = {
      ClockModule.class,
      SecretsModule.class,
      ObjectMapperModule.class,
      DynamoDbModule.class,
      RequestContextModule.class,
      AnkiBackupModule.class
    })
public interface AnkiBackupFactory {
  Clock clock();

  Secrets secrets();

  ObjectMapper objectMapper();

  RequestContextFactory requestContextFactory();

  HttpResponseFactory httpResponseFactory();

  DynamoDbTable<AnkiBackupItem> ankiBackupTable();

  S3Client s3Client();

  S3Presigner s3Presigner();

  static AnkiBackupFactory create() {
    return DaggerAnkiBackupFactory.create();
  }
}
