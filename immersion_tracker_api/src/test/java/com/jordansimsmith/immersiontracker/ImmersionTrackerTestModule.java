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
  public HttpResponseFactory httpResponseFactory(ObjectMapper objectMapper) {
    return new HttpResponseFactory.Builder(objectMapper).build();
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
  public FakeTvdbClient fakeTvdbClient() {
    return new FakeTvdbClient();
  }

  @Provides
  @Singleton
  public TvdbClient tvdbClient(FakeTvdbClient fakeTvdbClient) {
    return fakeTvdbClient;
  }

  @Provides
  @Singleton
  public FakeYoutubeClient fakeYoutubeClient() {
    return new FakeYoutubeClient();
  }

  @Provides
  @Singleton
  public YoutubeClient youtubeClient(FakeYoutubeClient fakeYoutubeClient) {
    return fakeYoutubeClient;
  }

  @Provides
  @Singleton
  public FakeSpotifyClient fakeSpotifyClient() {
    return new FakeSpotifyClient();
  }

  @Provides
  @Singleton
  public SpotifyClient spotifyClient(FakeSpotifyClient fakeSpotifyClient) {
    return fakeSpotifyClient;
  }
}
