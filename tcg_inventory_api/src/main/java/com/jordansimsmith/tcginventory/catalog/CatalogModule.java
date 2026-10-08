package com.jordansimsmith.tcginventory.catalog;

import com.fasterxml.jackson.databind.ObjectMapper;
import dagger.Module;
import dagger.Provides;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import javax.inject.Singleton;

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
  Catalogs catalogs(ScryfallCatalog scryfallCatalog) {
    return new Catalogs(Map.of("scryfall", scryfallCatalog, "tcgplayer", new TcgPlayerCatalog()));
  }
}
