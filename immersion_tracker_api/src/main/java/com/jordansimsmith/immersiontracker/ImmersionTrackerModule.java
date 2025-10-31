package com.jordansimsmith.immersiontracker;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jordansimsmith.secrets.Secrets;
import dagger.Module;
import dagger.Provides;
import java.net.http.HttpClient;
import javax.inject.Singleton;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;

@Module
public class ImmersionTrackerModule {
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
    var httpClient = HttpClient.newBuilder().build();
    return new HttpTvdbClient(objectMapper, secrets, httpClient);
  }

  @Provides
  @Singleton
  public YoutubeClient youtubeClient(ObjectMapper objectMapper, Secrets secrets) {
    var httpClient = HttpClient.newBuilder().build();
    return new HttpYoutubeClient(objectMapper, secrets, httpClient);
  }

  @Provides
  @Singleton
  public SpotifyClient spotifyClient(ObjectMapper objectMapper, Secrets secrets) {
    var httpClient = HttpClient.newBuilder().build();
    return new HttpSpotifyClient(objectMapper, secrets, httpClient);
  }
}
