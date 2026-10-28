package com.jordansimsmith.tcginventory.fetchtcg.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jordansimsmith.secrets.Secrets;
import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.service.ServiceException;
import com.jordansimsmith.tcginventory.fetchtcg.client.CreateListingImageRequest;
import com.jordansimsmith.tcginventory.fetchtcg.client.CreateListingImageResponse;
import com.jordansimsmith.tcginventory.fetchtcg.client.DeleteListingRequest;
import com.jordansimsmith.tcginventory.fetchtcg.client.DeleteListingResponse;
import com.jordansimsmith.tcginventory.fetchtcg.client.FetchTcgService;
import com.jordansimsmith.tcginventory.fetchtcg.client.FindCardListingsRequest;
import com.jordansimsmith.tcginventory.fetchtcg.client.FindCardListingsResponse;
import com.jordansimsmith.tcginventory.fetchtcg.client.FindOffersRequest;
import com.jordansimsmith.tcginventory.fetchtcg.client.FindOffersResponse;
import com.jordansimsmith.tcginventory.fetchtcg.client.GetAccountRequest;
import com.jordansimsmith.tcginventory.fetchtcg.client.GetAccountResponse;
import com.jordansimsmith.tcginventory.fetchtcg.client.GetCardRequest;
import com.jordansimsmith.tcginventory.fetchtcg.client.GetCardResponse;
import com.jordansimsmith.tcginventory.fetchtcg.client.UpdateCredentialsRequest;
import com.jordansimsmith.tcginventory.fetchtcg.client.UpdateCredentialsResponse;
import com.jordansimsmith.tcginventory.fetchtcg.client.UpdateListingRequest;
import com.jordansimsmith.tcginventory.fetchtcg.client.UpdateListingResponse;
import com.jordansimsmith.time.Clock;
import dagger.Module;
import dagger.Provides;
import java.net.URI;
import java.net.http.HttpClient;
import java.util.concurrent.ThreadLocalRandom;
import javax.inject.Named;
import javax.inject.Singleton;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;

@Module
public class FetchTcgModule {
  @Provides
  @Singleton
  FetchTcgService fetchTcgService(
      DynamoDbEnhancedClient dynamoDb,
      Secrets secrets,
      ObjectMapper objectMapper,
      Clock clock,
      @Named("tcgInventoryTableName") String tableName,
      @Named("firebaseTokenUri") URI firebaseTokenUri,
      @Named("fetchTcgBaseUri") URI fetchTcgBaseUri) {
    DynamoDbTable<FetchTcgAccountItem> accountTable =
        dynamoDb.table(tableName, TableSchema.fromBean(FetchTcgAccountItem.class));
    var accountRepository = new FetchTcgAccountRepository(accountTable);
    var httpClient = HttpClient.newHttpClient();
    Runnable pacer =
        () -> {
          try {
            Thread.sleep(ThreadLocalRandom.current().nextLong(500, 1000));
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
        };
    var tokenExchange = new HttpFetchTcgTokenExchange(firebaseTokenUri, httpClient, objectMapper);
    var fetchTcgClient = new HttpFetchTcgClient(fetchTcgBaseUri, httpClient, objectMapper, pacer);
    var credentialStore = new FetchTcgCredentialStore(secrets, objectMapper);
    var tokenProvider = new FetchTcgTokenProvider(tokenExchange, credentialStore, clock);
    var getAccountService = new GetAccountService(accountRepository);
    var getCardService = new GetCardService(fetchTcgClient);
    var findCardListingsService = new FindCardListingsService(fetchTcgClient);
    var findOffersService = new FindOffersService(tokenProvider, fetchTcgClient);
    var updateListingService = new UpdateListingService(tokenProvider, fetchTcgClient);
    var deleteListingService = new DeleteListingService(tokenProvider, fetchTcgClient);
    var createListingImageService = new CreateListingImageService(tokenProvider, fetchTcgClient);
    var updateCredentialsService =
        new UpdateCredentialsService(
            accountRepository, tokenExchange, fetchTcgClient, credentialStore, clock);

    return new FetchTcgService() {
      @Override
      public GetAccountResponse getAccount(RequestContext ctx, GetAccountRequest request)
          throws ServiceException {
        return getAccountService.getAccount(ctx, request);
      }

      @Override
      public UpdateCredentialsResponse updateCredentials(
          RequestContext ctx, UpdateCredentialsRequest request) throws ServiceException {
        return updateCredentialsService.updateCredentials(ctx, request);
      }

      @Override
      public GetCardResponse getCard(RequestContext ctx, GetCardRequest request)
          throws ServiceException {
        return getCardService.getCard(ctx, request);
      }

      @Override
      public FindCardListingsResponse findCardListings(
          RequestContext ctx, FindCardListingsRequest request) throws ServiceException {
        return findCardListingsService.findCardListings(ctx, request);
      }

      @Override
      public FindOffersResponse findOffers(RequestContext ctx, FindOffersRequest request)
          throws ServiceException {
        return findOffersService.findOffers(ctx, request);
      }

      @Override
      public UpdateListingResponse updateListing(RequestContext ctx, UpdateListingRequest request)
          throws ServiceException {
        return updateListingService.updateListing(ctx, request);
      }

      @Override
      public DeleteListingResponse deleteListing(RequestContext ctx, DeleteListingRequest request)
          throws ServiceException {
        return deleteListingService.deleteListing(ctx, request);
      }

      @Override
      public CreateListingImageResponse createListingImage(
          RequestContext ctx, CreateListingImageRequest request) throws ServiceException {
        return createListingImageService.createListingImage(ctx, request);
      }
    };
  }
}
