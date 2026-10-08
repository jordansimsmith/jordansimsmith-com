package com.jordansimsmith.tcginventory.catalog;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jordansimsmith.tcginventory.TcgInventoryTable;
import com.jordansimsmith.time.Clock;
import dagger.Module;
import dagger.Provides;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import javax.inject.Singleton;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.s3.S3Client;

@Module
public class CatalogModule {
  @Provides
  @Singleton
  HttpClient httpClient() {
    return HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(3))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build();
  }

  @Provides
  Runnable requestPacer() {
    var lastRequestAt = new long[] {0};
    return () -> {
      synchronized (lastRequestAt) {
        var waitMillis = 100 - (System.currentTimeMillis() - lastRequestAt[0]);
        if (waitMillis > 0) {
          try {
            Thread.sleep(waitMillis);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("HTTP request pacing was interrupted", e);
          }
        }
        lastRequestAt[0] = System.currentTimeMillis();
      }
    };
  }

  @Provides
  @Singleton
  ScryfallCatalog scryfallCatalog(
      ObjectMapper objectMapper, HttpClient httpClient, Runnable requestPacer) {
    var baseUrl = System.getenv("SCRYFALL_BASE_URL");
    if (baseUrl == null || baseUrl.isBlank()) {
      baseUrl = "https://api.scryfall.com";
    }
    return new ScryfallCatalog(URI.create(baseUrl), httpClient, objectMapper, requestPacer);
  }

  @Provides
  @Singleton
  TcgCsvClient tcgCsvClient(
      ObjectMapper objectMapper, HttpClient httpClient, Runnable requestPacer) {
    return new HttpTcgCsvClient(
        URI.create("https://tcgcsv.com"), httpClient, objectMapper, requestPacer);
  }

  @Provides
  @Singleton
  DynamoDbTable<CatalogSnapshotItem> catalogSnapshotTable(DynamoDbEnhancedClient client) {
    return client.table(
        TcgInventoryTable.TABLE_NAME, TableSchema.fromBean(CatalogSnapshotItem.class));
  }

  @Provides
  @Singleton
  CatalogRepository catalogRepository(
      DynamoDbTable<CatalogSnapshotItem> catalogSnapshotTable, DynamoDbClient dynamoDbClient) {
    return new CatalogRepository(catalogSnapshotTable, dynamoDbClient);
  }

  @Provides
  @Singleton
  CatalogSnapshotStore catalogSnapshotStore(
      S3Client s3Client, CatalogRepository catalogRepository, ObjectMapper objectMapper) {
    return new CatalogSnapshotStore(
        s3Client,
        CatalogSnapshotStore.BUCKET,
        catalogRepository,
        new TcgCsvCatalogArtifactCodec(objectMapper));
  }

  @Provides
  @Singleton
  Catalogs catalogs(
      ScryfallCatalog scryfallCatalog,
      CatalogRepository catalogRepository,
      CatalogSnapshotStore catalogSnapshotStore,
      Clock clock) {
    return new Catalogs(
        Map.of(
            "mtg",
            scryfallCatalog,
            "pokemon",
            new TcgPlayerCatalog("pokemon", catalogRepository, catalogSnapshotStore, clock)));
  }
}
