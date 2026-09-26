package com.jordansimsmith.tcginventory.fetchtcg;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jordansimsmith.secrets.Secrets;
import dagger.Module;
import dagger.Provides;
import java.net.URI;
import java.net.http.HttpClient;
import java.util.concurrent.ThreadLocalRandom;
import javax.inject.Singleton;

@Module
public class FetchTcgModule {
  @Provides
  @Singleton
  FetchTcgTokenMinter fetchTcgTokenMinter(ObjectMapper objectMapper, Secrets secrets) {
    var firebaseTokenUrl = System.getenv("FIREBASE_TOKEN_URL");
    if (firebaseTokenUrl == null || firebaseTokenUrl.isEmpty()) {
      firebaseTokenUrl =
          "https://securetoken.googleapis.com/v1/token?key=AIzaSyD7SVUprLrgU-bc0Oh756v17y5NKZNQBB8";
    }
    return new HttpFetchTcgTokenMinter(
        URI.create(firebaseTokenUrl), HttpClient.newHttpClient(), objectMapper, secrets);
  }

  @Provides
  @Singleton
  FetchTcgClient fetchTcgClient(ObjectMapper objectMapper) {
    Runnable pacer =
        () -> {
          try {
            Thread.sleep(ThreadLocalRandom.current().nextLong(1000, 2000));
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
          }
        };
    var fetchTcgBaseUrl = System.getenv("FETCHTCG_BASE_URL");
    if (fetchTcgBaseUrl == null || fetchTcgBaseUrl.isEmpty()) {
      fetchTcgBaseUrl = "https://api.fetchtcg.com";
    }
    return new HttpFetchTcgClient(
        URI.create(fetchTcgBaseUrl), HttpClient.newHttpClient(), objectMapper, pacer);
  }
}
