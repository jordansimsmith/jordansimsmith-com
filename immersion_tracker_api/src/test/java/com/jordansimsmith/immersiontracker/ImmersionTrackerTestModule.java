package com.jordansimsmith.immersiontracker;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jordansimsmith.http.HttpResponseFactory;
import dagger.Module;
import dagger.Provides;
import javax.inject.Singleton;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;

@Module
public class ImmersionTrackerTestModule {
  @Provides
  @Singleton
  HttpResponseFactory httpResponseFactory(ObjectMapper objectMapper) {
    return new HttpResponseFactory.Builder(objectMapper)
        .withAllowedOrigin("https://immersion-tracker.jordansimsmith.com")
        .build();
  }

  @Provides
  @Singleton
  DynamoDbTable<ImmersionTrackerItem> immersionTrackerTable(
      DynamoDbEnhancedClient dynamoDbEnhancedClient) {
    var schema = TableSchema.fromBean(ImmersionTrackerItem.class);
    return dynamoDbEnhancedClient.table("immersion_tracker", schema);
  }

  @Provides
  @Singleton
  FakeTvdbClient fakeTvdbClient() {
    return new FakeTvdbClient();
  }

  @Provides
  @Singleton
  TvdbClient tvdbClient(FakeTvdbClient fakeTvdbClient) {
    return fakeTvdbClient;
  }

  @Provides
  @Singleton
  FakeYoutubeClient fakeYoutubeClient() {
    return new FakeYoutubeClient();
  }

  @Provides
  @Singleton
  YoutubeClient youtubeClient(FakeYoutubeClient fakeYoutubeClient) {
    return fakeYoutubeClient;
  }

  @Provides
  @Singleton
  FakeSpotifyClient fakeSpotifyClient() {
    return new FakeSpotifyClient();
  }

  @Provides
  @Singleton
  SpotifyClient spotifyClient(FakeSpotifyClient fakeSpotifyClient) {
    return fakeSpotifyClient;
  }
}
