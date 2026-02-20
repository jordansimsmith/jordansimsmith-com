package com.jordansimsmith.immersiontracker;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jordansimsmith.http.HttpResponseFactory;
import com.jordansimsmith.secrets.Secrets;
import dagger.Module;
import dagger.Provides;
import java.net.URI;
import java.net.http.HttpClient;
import javax.inject.Singleton;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;

@Module
public class ImmersionTrackerModule {
  @Provides
  @Singleton
  public HttpResponseFactory httpResponseFactory(ObjectMapper objectMapper) {
    return new HttpResponseFactory.Builder(objectMapper)
        .withAllowedOrigin("https://immersion-tracker.jordansimsmith.com")
        .build();
  }

  @Provides
  @Singleton
  public DynamoDbTable<ImmersionTrackerItem> immersionTrackerTable(
      DynamoDbEnhancedClient dynamoDbEnhancedClient) {
    var schema = TableSchema.fromBean(ImmersionTrackerItem.class);
    return dynamoDbEnhancedClient.table("immersion_tracker", schema);
  }

  @Provides
  @Singleton
  public TvdbClient tvdbClient(ObjectMapper objectMapper, Secrets secrets) {
    var tvdbBaseUrl = System.getenv("IMMERSION_TRACKER_TVDB_BASE_URL");
    if (tvdbBaseUrl == null || tvdbBaseUrl.isBlank()) {
      tvdbBaseUrl = "https://api4.thetvdb.com";
    }
    var httpClient = HttpClient.newBuilder().build();
    return new HttpTvdbClient(URI.create(tvdbBaseUrl), objectMapper, secrets, httpClient);
  }

  @Provides
  @Singleton
  public YoutubeClient youtubeClient(ObjectMapper objectMapper, Secrets secrets) {
    var youtubeBaseUrl = System.getenv("IMMERSION_TRACKER_YOUTUBE_BASE_URL");
    if (youtubeBaseUrl == null || youtubeBaseUrl.isBlank()) {
      youtubeBaseUrl = "https://www.googleapis.com";
    }
    var httpClient = HttpClient.newBuilder().build();
    return new HttpYoutubeClient(URI.create(youtubeBaseUrl), objectMapper, secrets, httpClient);
  }

  @Provides
  @Singleton
  public SpotifyClient spotifyClient(ObjectMapper objectMapper, Secrets secrets) {
    var spotifyApiBaseUrl = System.getenv("IMMERSION_TRACKER_SPOTIFY_API_BASE_URL");
    if (spotifyApiBaseUrl == null || spotifyApiBaseUrl.isBlank()) {
      spotifyApiBaseUrl = "https://api.spotify.com";
    }
    var spotifyAccountsBaseUrl = System.getenv("IMMERSION_TRACKER_SPOTIFY_ACCOUNTS_BASE_URL");
    if (spotifyAccountsBaseUrl == null || spotifyAccountsBaseUrl.isBlank()) {
      spotifyAccountsBaseUrl = "https://accounts.spotify.com";
    }
    var httpClient = HttpClient.newBuilder().build();
    return new HttpSpotifyClient(
        URI.create(spotifyApiBaseUrl),
        URI.create(spotifyAccountsBaseUrl),
        objectMapper,
        secrets,
        httpClient);
  }
}
