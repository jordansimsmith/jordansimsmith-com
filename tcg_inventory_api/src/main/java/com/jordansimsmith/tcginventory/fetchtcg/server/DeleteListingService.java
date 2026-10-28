package com.jordansimsmith.tcginventory.fetchtcg.server;

import com.jordansimsmith.service.ConflictException;
import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.service.ServiceException;
import com.jordansimsmith.tcginventory.fetchtcg.client.DeleteListingRequest;
import com.jordansimsmith.tcginventory.fetchtcg.client.DeleteListingResponse;

public class DeleteListingService {
  private static final String RECONNECT_MESSAGE =
      "Save or re-save your FetchTCG refresh token in settings to verify your seller account.";

  private final FetchTcgTokenProvider tokenProvider;
  private final HttpFetchTcgClient fetchTcgClient;

  public DeleteListingService(
      FetchTcgTokenProvider tokenProvider, HttpFetchTcgClient fetchTcgClient) {
    this.tokenProvider = tokenProvider;
    this.fetchTcgClient = fetchTcgClient;
  }

  public DeleteListingResponse deleteListing(RequestContext ctx, DeleteListingRequest request)
      throws ServiceException {
    var bearerToken = tokenProvider.getBearer(ctx.user());
    try {
      fetchTcgClient.deleteListing(bearerToken, request.listingId());
      return new DeleteListingResponse();
    } catch (FetchTcgAuthenticationException e) {
      throw new ConflictException(RECONNECT_MESSAGE);
    }
  }
}
