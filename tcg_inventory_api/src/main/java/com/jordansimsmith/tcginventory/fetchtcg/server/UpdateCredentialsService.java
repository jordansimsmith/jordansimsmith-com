package com.jordansimsmith.tcginventory.fetchtcg.server;

import com.jordansimsmith.service.BadRequestException;
import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.service.ServiceException;
import com.jordansimsmith.tcginventory.fetchtcg.client.UpdateCredentialsRequest;
import com.jordansimsmith.tcginventory.fetchtcg.client.UpdateCredentialsResponse;
import com.jordansimsmith.time.Clock;
import java.time.temporal.ChronoUnit;

public class UpdateCredentialsService {
  private final FetchTcgAccountRepository accountRepository;
  private final HttpFetchTcgTokenExchange tokenExchange;
  private final HttpFetchTcgClient fetchTcgClient;
  private final FetchTcgCredentialStore credentialStore;
  private final Clock clock;

  public UpdateCredentialsService(
      FetchTcgAccountRepository accountRepository,
      HttpFetchTcgTokenExchange tokenExchange,
      HttpFetchTcgClient fetchTcgClient,
      FetchTcgCredentialStore credentialStore,
      Clock clock) {
    this.accountRepository = accountRepository;
    this.tokenExchange = tokenExchange;
    this.fetchTcgClient = fetchTcgClient;
    this.credentialStore = credentialStore;
    this.clock = clock;
  }

  public UpdateCredentialsResponse updateCredentials(
      RequestContext ctx, UpdateCredentialsRequest request) throws ServiceException {
    if (request == null || request.refreshToken() == null || request.refreshToken().isBlank()) {
      throw new BadRequestException("refresh_token is required");
    }

    // todo: reject credential updates during an active background task through the jobs client.
    var tokenExchangeResult = tokenExchange.exchange(request.refreshToken());

    HttpFetchTcgClient.GetProfileResponse profile;
    try {
      profile = fetchTcgClient.getProfile(tokenExchangeResult.bearerToken());
    } catch (FetchTcgAuthenticationException e) {
      throw new BadRequestException("The refresh token was rejected. Check it and try again.");
    }

    var credentialsUpdatedAt = clock.now().truncatedTo(ChronoUnit.SECONDS);
    credentialStore.updateRefreshToken(ctx.user(), tokenExchangeResult.refreshToken());
    accountRepository.update(
        FetchTcgAccountItem.create(ctx.user(), profile.username(), credentialsUpdatedAt));

    return new UpdateCredentialsResponse(profile.username(), credentialsUpdatedAt.getEpochSecond());
  }
}
