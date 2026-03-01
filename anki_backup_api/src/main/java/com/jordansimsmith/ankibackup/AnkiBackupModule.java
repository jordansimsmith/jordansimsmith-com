package com.jordansimsmith.ankibackup;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jordansimsmith.http.HttpResponseFactory;
import dagger.Module;
import dagger.Provides;
import java.net.URI;
import javax.inject.Singleton;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

@Module
public class AnkiBackupModule {
  @Provides
  @Singleton
  public HttpResponseFactory httpResponseFactory(ObjectMapper objectMapper) {
    return new HttpResponseFactory.Builder(objectMapper).build();
  }

  @Provides
  @Singleton
  public DynamoDbTable<AnkiBackupItem> ankiBackupTable(
      DynamoDbEnhancedClient dynamoDbEnhancedClient) {
    var schema = TableSchema.fromBean(AnkiBackupItem.class);
    return dynamoDbEnhancedClient.table("anki_backup", schema);
  }

  @Provides
  @Singleton
  public S3Client s3Client() {
    return S3Client.builder().forcePathStyle(true).build();
  }

  @Provides
  @Singleton
  public S3Presigner s3Presigner() {
    // S3Presigner does not auto-detect AWS_ENDPOINT_URL unlike S3Client
    var builder = S3Presigner.builder();
    var endpointUrl = System.getenv("AWS_ENDPOINT_URL");
    if (endpointUrl != null && !endpointUrl.isBlank()) {
      builder
          .endpointOverride(URI.create(endpointUrl))
          .region(Region.of(System.getenv("AWS_REGION")))
          .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build());
    }
    return builder.build();
  }
}
