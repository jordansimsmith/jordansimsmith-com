package com.jordansimsmith.tcginventory.fetchtcg.server;

import com.jordansimsmith.service.BadRequestException;
import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.service.ServiceException;
import com.jordansimsmith.tcginventory.fetchtcg.client.GetCardRequest;
import com.jordansimsmith.tcginventory.fetchtcg.client.GetCardResponse;
import java.util.Map;

public class GetCardService {
  private final HttpFetchTcgClient fetchTcgClient;

  public GetCardService(HttpFetchTcgClient fetchTcgClient) {
    this.fetchTcgClient = fetchTcgClient;
  }

  public GetCardResponse getCard(RequestContext ctx, GetCardRequest request)
      throws ServiceException {
    if (request == null || request.cardId() == null || request.cardId().isBlank()) {
      throw new BadRequestException("card_id is required");
    }

    var providerResponse = fetchTcgClient.getCard(request.cardId());
    return new GetCardResponse(
        providerResponse.id(),
        providerResponse.name(),
        providerResponse.marketPrice(),
        providerResponse.externalReferences() != null
            ? providerResponse.externalReferences()
            : Map.of());
  }
}
