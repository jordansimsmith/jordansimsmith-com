package com.jordansimsmith.footballcalendar;

import com.fasterxml.jackson.databind.ObjectMapper;
import dagger.Module;
import dagger.Provides;
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
    var httpClient = HttpClient.newBuilder().build();
    return new HttpCometClient(httpClient, objectMapper);
  }

  @Provides
  @Singleton
  FootballFixClient footballFixClient() {
    return new JsoupFootballFixClient();
  }

  @Provides
  @Singleton
  SubfootballClient subfootballClient() {
    var httpClient = HttpClient.newBuilder().build();
    return new BiweeklySubfootballClient(httpClient);
  }

  @Provides
  @Singleton
  TeamsFactory teamsFactory() {
    return new TeamsFactoryImpl();
  }
}
