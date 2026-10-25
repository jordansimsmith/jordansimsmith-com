package com.jordansimsmith.tcginventory.fetchtcg;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jordansimsmith.secrets.Secrets;
import dagger.Module;
import dagger.Provides;
import java.net.URI;
import java.net.http.HttpClient;
import java.util.concurrent.ThreadLocalRandom;
import javax.inject.Named;
import javax.inject.Singleton;

@Module
public class LegacyFetchTcgModule {
  @Provides
  @Singleton
  FetchTcgTokenMinter fetchTcgTokenMinter(
      ObjectMapper objectMapper, Secrets secrets, @Named("firebaseTokenUri") URI firebaseTokenUri) {
    return new HttpFetchTcgTokenMinter(
        firebaseTokenUri, HttpClient.newHttpClient(), objectMapper, secrets);
  }

  @Provides
  @Singleton
  FetchTcgClient fetchTcgClient(
      ObjectMapper objectMapper, @Named("fetchTcgBaseUri") URI fetchTcgBaseUri) {
    Runnable pacer =
        () -> {
          try {
            Thread.sleep(ThreadLocalRandom.current().nextLong(500, 1000));
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
          }
        };
    return new HttpFetchTcgClient(fetchTcgBaseUri, HttpClient.newHttpClient(), objectMapper, pacer);
  }
}
