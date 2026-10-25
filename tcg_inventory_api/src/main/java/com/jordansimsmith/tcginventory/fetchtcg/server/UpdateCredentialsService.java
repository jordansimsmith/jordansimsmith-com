package com.jordansimsmith.tcginventory.fetchtcg.server;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jordansimsmith.secrets.Secrets;
import com.jordansimsmith.service.BadRequestException;
import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.service.ServiceException;
import com.jordansimsmith.tcginventory.fetchtcg.client.UpdateCredentialsRequest;
import com.jordansimsmith.tcginventory.fetchtcg.client.UpdateCredentialsResponse;
import com.jordansimsmith.time.Clock;
import java.io.IOException;
import java.time.temporal.ChronoUnit;

public class UpdateCredentialsService {
  private static final String SECRET_NAME = "tcg_inventory";

  private final FetchTcgAccountRepository accountRepository;
  private final HttpFetchTcgTokenExchange tokenExchange;
  private final HttpFetchTcgClient fetchTcgClient;
  private final Secrets secrets;
  private final ObjectMapper objectMapper;
  private final Clock clock;

  public UpdateCredentialsService(
      FetchTcgAccountRepository accountRepository,
      HttpFetchTcgTokenExchange tokenExchange,
      HttpFetchTcgClient fetchTcgClient,
      Secrets secrets,
      ObjectMapper objectMapper,
      Clock clock) {
    this.accountRepository = accountRepository;
    this.tokenExchange = tokenExchange;
    this.fetchTcgClient = fetchTcgClient;
    this.secrets = secrets;
    this.objectMapper = objectMapper;
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
    saveCredential(ctx.user(), tokenExchangeResult.refreshToken());
    accountRepository.update(
        FetchTcgAccountItem.create(ctx.user(), profile.username(), credentialsUpdatedAt));

    return new UpdateCredentialsResponse(profile.username(), credentialsUpdatedAt.getEpochSecond());
  }

  private void saveCredential(String user, String refreshToken) {
    String existingSecret;
    try {
      existingSecret = secrets.get(SECRET_NAME);
    } catch (RuntimeException e) {
      throw new IllegalStateException("Failed to save FetchTCG credentials");
    }
    if (existingSecret == null) {
      throw new IllegalStateException("FetchTCG credential storage is malformed");
    }

    ObjectNode secretNode;
    try {
      var parsedSecret = objectMapper.readTree(existingSecret);
      if (parsedSecret == null || !parsedSecret.isObject()) {
        throw new IllegalStateException("FetchTCG credential storage is malformed");
      }
      secretNode = (ObjectNode) parsedSecret;
    } catch (IOException e) {
      throw new IllegalStateException("FetchTCG credential storage is malformed");
    }

    secretNode.put(user, refreshToken);
    String updatedSecret;
    try {
      updatedSecret = objectMapper.writeValueAsString(secretNode);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Failed to save FetchTCG credentials");
    }

    try {
      secrets.put(SECRET_NAME, updatedSecret);
    } catch (RuntimeException e) {
      throw new IllegalStateException("Failed to save FetchTCG credentials");
    }
  }
}
