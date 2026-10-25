package com.jordansimsmith.tcginventory.fetchtcg.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jordansimsmith.secrets.Secrets;
import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.service.ServiceException;
import com.jordansimsmith.tcginventory.fetchtcg.client.FetchTcgService;
import com.jordansimsmith.tcginventory.fetchtcg.client.GetAccountRequest;
import com.jordansimsmith.tcginventory.fetchtcg.client.GetAccountResponse;
import com.jordansimsmith.tcginventory.fetchtcg.client.UpdateCredentialsRequest;
import com.jordansimsmith.tcginventory.fetchtcg.client.UpdateCredentialsResponse;
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
            throw new IllegalStateException("FetchTCG request pacing was interrupted");
          }
        };
    var tokenExchange = new HttpFetchTcgTokenExchange(firebaseTokenUri, httpClient, objectMapper);
    var fetchTcgClient = new HttpFetchTcgClient(fetchTcgBaseUri, httpClient, objectMapper, pacer);
    var getAccountService = new GetAccountService(accountRepository);
    var updateCredentialsService =
        new UpdateCredentialsService(
            accountRepository, tokenExchange, fetchTcgClient, secrets, objectMapper, clock);

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
    };
  }
}
