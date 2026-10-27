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
import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
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
  ExcludedSellerUsernameFactory excludedSellerUsernameFactory() {
    return new ExcludedSellerUsernameFactoryImpl();
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
  HttpClient tradeMeHttpClient() {
    return HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build();
  }

  @Provides
  @Singleton
  Runnable tradeMeRequestPacer() {
    return () -> {
      try {
        Thread.sleep(ThreadLocalRandom.current().nextLong(100, 201));
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("Trade Me request pacing was interrupted", e);
      }
    };
  }

  @Provides
  @Singleton
  TradeMeClient tradeMeClient(
      ObjectMapper objectMapper, HttpClient tradeMeHttpClient, Runnable tradeMeRequestPacer) {
    var apiBaseUrl = System.getenv("AUCTION_TRACKER_TRADEME_API_BASE_URL");
    if (apiBaseUrl == null || apiBaseUrl.isBlank()) {
      apiBaseUrl = "https://api.trademe.co.nz";
    }
    return new HttpTradeMeClient(
        URI.create(apiBaseUrl), objectMapper, tradeMeHttpClient, tradeMeRequestPacer);
  }

  @Provides
  @Singleton
  ListingFingerprinter listingFingerprinter() {
    return new Sha256ListingFingerprinter();
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
