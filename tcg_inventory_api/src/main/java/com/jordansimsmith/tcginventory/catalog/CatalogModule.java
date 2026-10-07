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
  ScryfallCatalog scryfallCatalog(ObjectMapper objectMapper) {
    var baseUrl = System.getenv("SCRYFALL_BASE_URL");
    if (baseUrl == null || baseUrl.isBlank()) {
      baseUrl = "https://api.scryfall.com";
    }
    var httpClient =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    var lastRequestAt = new long[] {0};
    Runnable pacer =
        () -> {
          synchronized (lastRequestAt) {
            var waitMillis = 100 - (System.currentTimeMillis() - lastRequestAt[0]);
            if (waitMillis > 0) {
              try {
                Thread.sleep(waitMillis);
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new CatalogException.Unavailable("catalog request was interrupted", e);
              }
            }
            lastRequestAt[0] = System.currentTimeMillis();
          }
        };
    return new ScryfallCatalog(URI.create(baseUrl), httpClient, objectMapper, pacer);
  }

  @Provides
  @Singleton
  Catalogs catalogs(ScryfallCatalog scryfallCatalog) {
    return new Catalogs(Map.of("scryfall", scryfallCatalog, "tcgplayer", new TcgPlayerCatalog()));
  }
}
