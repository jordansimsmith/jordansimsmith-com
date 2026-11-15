package com.jordansimsmith.tcginventory.catalog.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.service.ServiceException;
import com.jordansimsmith.tcginventory.catalog.client.CatalogService;
import com.jordansimsmith.tcginventory.catalog.client.FindCardAlternativesRequest;
import com.jordansimsmith.tcginventory.catalog.client.FindCardAlternativesResponse;
import com.jordansimsmith.tcginventory.catalog.client.FindCardImagesRequest;
import com.jordansimsmith.tcginventory.catalog.client.FindCardImagesResponse;
import com.jordansimsmith.tcginventory.catalog.client.FindCardsRequest;
import com.jordansimsmith.tcginventory.catalog.client.FindCardsResponse;
import com.jordansimsmith.tcginventory.catalog.client.GetCardRequest;
import com.jordansimsmith.tcginventory.catalog.client.GetCardResponse;
import com.jordansimsmith.tcginventory.catalog.client.SearchCardsRequest;
import com.jordansimsmith.tcginventory.catalog.client.SearchCardsResponse;
import com.jordansimsmith.tcginventory.games.client.GamesService;
import com.jordansimsmith.time.Clock;
import dagger.Module;
import dagger.Provides;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import javax.inject.Named;
import javax.inject.Singleton;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.s3.S3Client;

@Module
public class CatalogModule {
  @Provides
  @Singleton
  CatalogService catalogService(
      GamesService gamesService,
      ObjectMapper objectMapper,
      Clock clock,
      @Named("scryfallBaseUri") URI scryfallBaseUri,
      CatalogRepository repository,
      CatalogSnapshotStore snapshotStore) {
    var findCardImagesService = new FindCardImagesService(gamesService);
    var httpClient =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    var magicTheGathering =
        new MagicTheGatheringPrintingLookup(scryfallBaseUri, httpClient, objectMapper);
    var pokemon = new PokemonPrintingLookup(repository, snapshotStore, clock);
    var getCardService = new GetCardService(gamesService, magicTheGathering, pokemon);
    var findCardsService = new FindCardsService(gamesService, magicTheGathering, pokemon);
    var searchCardsService = new SearchCardsService(gamesService, magicTheGathering, pokemon);
    var findCardAlternativesService =
        new FindCardAlternativesService(gamesService, magicTheGathering, pokemon);
    return new CatalogService() {
      @Override
      public GetCardResponse getCard(RequestContext ctx, GetCardRequest request)
          throws ServiceException {
        return getCardService.getCard(ctx, request);
      }

      @Override
      public FindCardImagesResponse findCardImages(
          RequestContext ctx, FindCardImagesRequest request) throws ServiceException {
        return findCardImagesService.findCardImages(ctx, request);
      }

      @Override
      public FindCardsResponse findCards(RequestContext ctx, FindCardsRequest request)
          throws ServiceException {
        return findCardsService.findCards(ctx, request);
      }

      @Override
      public SearchCardsResponse searchCards(RequestContext ctx, SearchCardsRequest request)
          throws ServiceException {
        return searchCardsService.searchCards(ctx, request);
      }

      @Override
      public FindCardAlternativesResponse findCardAlternatives(
          RequestContext ctx, FindCardAlternativesRequest request) throws ServiceException {
        return findCardAlternativesService.findCardAlternatives(ctx, request);
      }
    };
  }

  @Provides
  @Singleton
  CatalogRepository catalogRepository(
      DynamoDbEnhancedClient enhancedClient,
      DynamoDbClient dynamoDbClient,
      @Named("tcgInventoryTableName") String tableName) {
    var table = enhancedClient.table(tableName, TableSchema.fromBean(CatalogSnapshotItem.class));
    return new CatalogRepository(table, dynamoDbClient);
  }

  @Provides
  @Singleton
  CatalogSnapshotStore catalogSnapshotStore(
      S3Client s3,
      @Named("catalogSnapshotBucket") String bucket,
      CatalogRepository repository,
      ObjectMapper objectMapper) {
    return new CatalogSnapshotStore(
        s3, bucket, repository, new TcgCsvCatalogArtifactCodec(objectMapper));
  }

  @Provides
  @Singleton
  CatalogRefreshMessageHandler catalogRefreshMessageHandler(
      ObjectMapper objectMapper,
      CatalogRepository repository,
      CatalogSnapshotStore store,
      Clock clock) {
    var httpClient =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    var lastRequestAt = new long[] {0};
    Runnable requestPacer =
        () -> {
          synchronized (lastRequestAt) {
            var waitMillis = 100 - (System.currentTimeMillis() - lastRequestAt[0]);
            if (waitMillis > 0) {
              try {
                Thread.sleep(waitMillis);
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("HTTP request pacing was interrupted", e);
              }
            }
            lastRequestAt[0] = System.currentTimeMillis();
          }
        };
    var tcgCsvClient =
        new HttpTcgCsvClient(
            URI.create("https://tcgcsv.com"), httpClient, objectMapper, requestPacer);
    return new CatalogRefreshMessageHandler(
        tcgCsvClient, new TcgCsvCatalogNormalizer(), store, repository, clock);
  }
}
