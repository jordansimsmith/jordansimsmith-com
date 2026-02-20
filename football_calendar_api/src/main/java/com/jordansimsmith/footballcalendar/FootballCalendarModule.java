package com.jordansimsmith.footballcalendar;

import com.fasterxml.jackson.databind.ObjectMapper;
import dagger.Module;
import dagger.Provides;
import java.net.URI;
import java.net.http.HttpClient;
import javax.inject.Singleton;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;

@Module
public class FootballCalendarModule {

  @Provides
  @Singleton
  DynamoDbTable<FootballCalendarItem> footballCalendarTable(
      DynamoDbEnhancedClient dynamoDbEnhancedClient) {
    var schema = TableSchema.fromBean(FootballCalendarItem.class);
    return dynamoDbEnhancedClient.table("football_calendar", schema);
  }

  @Provides
  @Singleton
  CometClient cometClient(ObjectMapper objectMapper) {
    var apiUrl = System.getenv("FOOTBALL_CALENDAR_COMET_API_URL");
    if (apiUrl == null || apiUrl.isBlank()) {
      apiUrl = "https://www.nrf.org.nz";
    }
    var httpClient = HttpClient.newBuilder().build();
    return new HttpCometClient(httpClient, objectMapper, URI.create(apiUrl));
  }

  @Provides
  @Singleton
  FootballFixClient footballFixClient() {
    var baseUrl = System.getenv("FOOTBALL_CALENDAR_FOOTBALL_FIX_BASE_URL");
    if (baseUrl == null || baseUrl.isBlank()) {
      baseUrl = "https://footballfix.spawtz.com";
    }
    return new JsoupFootballFixClient(URI.create(baseUrl));
  }

  @Provides
  @Singleton
  SubfootballClient subfootballClient() {
    var baseUrl = System.getenv("FOOTBALL_CALENDAR_SUBFOOTBALL_BASE_URL");
    if (baseUrl == null || baseUrl.isBlank()) {
      baseUrl = "https://subfootball.com";
    }
    var httpClient = HttpClient.newBuilder().build();
    return new BiweeklySubfootballClient(httpClient, URI.create(baseUrl));
  }

  @Provides
  @Singleton
  TeamsFactory teamsFactory() {
    return new TeamsFactoryImpl();
  }
}
