package com.jordansimsmith.immersiontracker;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jordansimsmith.dynamodb.DynamoDbModule;
import com.jordansimsmith.http.RequestContextFactory;
import com.jordansimsmith.http.RequestContextModule;
import com.jordansimsmith.json.ObjectMapperModule;
import com.jordansimsmith.secrets.Secrets;
import com.jordansimsmith.secrets.SecretsModule;
import com.jordansimsmith.time.Clock;
import com.jordansimsmith.time.ClockModule;
import dagger.Component;
import javax.inject.Singleton;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;

@Singleton
@Component(
    modules = {
      ClockModule.class,
      SecretsModule.class,
      ObjectMapperModule.class,
      DynamoDbModule.class,
      RequestContextModule.class,
      ImmersionTrackerModule.class
    })
public interface ImmersionTrackerFactory {
  Clock clock();

  Secrets secrets();

  ObjectMapper objectMapper();

  RequestContextFactory requestContextFactory();

  DynamoDbTable<ImmersionTrackerItem> immersionTrackerTable();

  TvdbClient tvdbClient();

  YoutubeClient youtubeClient();

  SpotifyClient spotifyClient();

  static ImmersionTrackerFactory create() {
    return DaggerImmersionTrackerFactory.create();
  }
}
