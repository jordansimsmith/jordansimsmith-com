package com.jordansimsmith.auctiontracker;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jordansimsmith.llm.LlmClient;
import com.jordansimsmith.llm.OpenAiLlmClient;
import com.jordansimsmith.prompts.PromptRegistry;
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
public class AuctionTrackerModule {
  @Provides
  @Singleton
  DynamoDbTable<AuctionTrackerItem> auctionTrackerTable(
      DynamoDbEnhancedClient dynamoDbEnhancedClient) {
    var schema = TableSchema.fromBean(AuctionTrackerItem.class);
    return dynamoDbEnhancedClient.table("auction_tracker", schema);
  }

  @Provides
  @Singleton
  SearchFactory searchFactory() {
    var baseUrl = System.getenv("AUCTION_TRACKER_TRADEME_BASE_URL");
    if (baseUrl == null || baseUrl.isBlank()) {
      baseUrl = "https://www.trademe.co.nz";
    }
    return new SearchFactoryImpl(URI.create(baseUrl));
  }

  @Provides
  @Singleton
  TradeMeClient tradeMeClient() {
    return new JsoupTradeMeClient();
  }

  @Provides
  @Singleton
  LlmClient llmClient(ObjectMapper objectMapper, Secrets secrets) {
    var openAiBaseUrl = System.getenv("AUCTION_TRACKER_OPENAI_BASE_URL");
    if (openAiBaseUrl == null || openAiBaseUrl.isBlank()) {
      openAiBaseUrl = "https://api.openai.com";
    }
    var httpClient = HttpClient.newBuilder().build();
    return new OpenAiLlmClient(
        URI.create(openAiBaseUrl), objectMapper, secrets, "auction_tracker_api", httpClient);
  }

  @Provides
  @Singleton
  ListingJudge listingJudge(
      PromptRegistry promptRegistry, LlmClient llmClient, ObjectMapper objectMapper) {
    return new LlmListingJudge(promptRegistry, llmClient, objectMapper);
  }
}
