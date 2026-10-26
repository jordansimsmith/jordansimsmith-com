package com.jordansimsmith.tcginventory.fetchtcg.server;

import com.jordansimsmith.service.BadRequestException;
import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.service.ServiceException;
import com.jordansimsmith.tcginventory.fetchtcg.client.CardListing;
import com.jordansimsmith.tcginventory.fetchtcg.client.FindCardListingsRequest;
import com.jordansimsmith.tcginventory.fetchtcg.client.FindCardListingsResponse;

public class FindCardListingsService {
  private static final int DEFAULT_LIMIT = 100;
  private static final int MAX_LIMIT = 100;

  private final HttpFetchTcgClient fetchTcgClient;

  public FindCardListingsService(HttpFetchTcgClient fetchTcgClient) {
    this.fetchTcgClient = fetchTcgClient;
  }

  public FindCardListingsResponse findCardListings(
      RequestContext ctx, FindCardListingsRequest request) throws ServiceException {
    if (request == null || request.cardId() == null || request.cardId().isBlank()) {
      throw new BadRequestException("card_id is required");
    }

    var cardId = request.cardId();
    var limit = request.limit() == null ? DEFAULT_LIMIT : request.limit();
    validateLimit(limit);
    int pageOffset;
    if (request.continuation() == null) {
      pageOffset = 0;
    } else {
      pageOffset = decodeContinuation(request.continuation());
      if (pageOffset < 1) {
        throw new BadRequestException("continuation is invalid");
      }
    }

    var page = fetchTcgClient.findCardListings(cardId, limit, pageOffset);
    if (page.number() == null
        || page.number() != pageOffset
        || page.size() == null
        || page.size() != limit
        || page.last() == null
        || page.content() == null
        || page.content().size() > limit) {
      throw new IllegalStateException("FetchTCG listings response contained invalid pagination");
    }

    var listings =
        page.content().stream()
            .map(
                listing ->
                    new CardListing(
                        listing.id(),
                        listing.condition(),
                        listing.listedPriceInRequestedCurrency(),
                        listing.sellerProfileName()))
            .toList();
    if (!page.last() && pageOffset == Integer.MAX_VALUE) {
      throw new IllegalStateException("FetchTCG listings response exceeded the page range");
    }
    var nextContinuation = page.last() ? null : encodeContinuation(pageOffset + 1);
    return new FindCardListingsResponse(listings, nextContinuation);
  }

  private void validateLimit(int limit) throws BadRequestException {
    if (limit < 1 || limit > MAX_LIMIT) {
      throw new BadRequestException("limit must be between 1 and 100");
    }
  }

  private int decodeContinuation(String token) throws BadRequestException {
    try {
      return Integer.parseInt(token);
    } catch (NumberFormatException e) {
      throw new BadRequestException("continuation is invalid");
    }
  }

  private String encodeContinuation(int pageOffset) {
    return Integer.toString(pageOffset);
  }
}
