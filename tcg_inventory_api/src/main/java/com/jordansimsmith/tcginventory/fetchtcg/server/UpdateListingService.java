package com.jordansimsmith.tcginventory.fetchtcg.server;

import com.jordansimsmith.service.ConflictException;
import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.service.ServiceException;
import com.jordansimsmith.tcginventory.fetchtcg.client.UpdateListingRequest;
import com.jordansimsmith.tcginventory.fetchtcg.client.UpdateListingResponse;

public class UpdateListingService {
  private static final String RECONNECT_MESSAGE =
      "Save or re-save your FetchTCG refresh token in settings to verify your seller account.";

  private final FetchTcgTokenProvider tokenProvider;
  private final HttpFetchTcgClient fetchTcgClient;

  public UpdateListingService(
      FetchTcgTokenProvider tokenProvider, HttpFetchTcgClient fetchTcgClient) {
    this.tokenProvider = tokenProvider;
    this.fetchTcgClient = fetchTcgClient;
  }

  public UpdateListingResponse updateListing(RequestContext ctx, UpdateListingRequest request)
      throws ServiceException {
    var bearerToken = tokenProvider.getBearer(ctx.user());
    try {
      return fetchTcgClient.updateListing(bearerToken, request);
    } catch (FetchTcgAuthenticationException e) {
      throw new ConflictException(RECONNECT_MESSAGE);
    }
  }
}
