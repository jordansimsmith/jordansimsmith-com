package com.jordansimsmith.tcginventory.catalog.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.service.ServiceException;
import com.jordansimsmith.tcginventory.catalog.client.CatalogService;
import com.jordansimsmith.tcginventory.catalog.client.FindCardImagesRequest;
import com.jordansimsmith.tcginventory.catalog.client.FindCardImagesResponse;
import com.jordansimsmith.tcginventory.catalog.client.FindCatalogCardsRequest;
import com.jordansimsmith.tcginventory.catalog.client.FindCatalogCardsResponse;
import com.jordansimsmith.tcginventory.games.client.GamesService;
import com.jordansimsmith.time.Clock;
import dagger.Module;
import dagger.Provides;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import javax.inject.Named;
import javax.inject.Singleton;
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
      @Named("tcgInventoryTableName") String tableName,
      @Named("catalogSnapshotBucket") String snapshotBucket,
      DynamoDbClient dynamoDb,
      S3Client s3) {
    var findCardImagesService = new FindCardImagesService(gamesService);
    var httpClient =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    var findCatalogCardsService =
        new FindCatalogCardsService(
            gamesService,
            new MagicTheGatheringPrintingLookup(scryfallBaseUri, httpClient, objectMapper),
            new PokemonPrintingLookup(
                dynamoDb, s3, tableName, snapshotBucket, objectMapper, clock));
    return new CatalogService() {
      @Override
      public FindCardImagesResponse findCardImages(
          RequestContext ctx, FindCardImagesRequest request) throws ServiceException {
        return findCardImagesService.findCardImages(ctx, request);
      }

      @Override
      public FindCatalogCardsResponse findCards(RequestContext ctx, FindCatalogCardsRequest request)
          throws ServiceException {
        return findCatalogCardsService.findCards(ctx, request);
      }
    };
  }
}
