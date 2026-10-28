package com.jordansimsmith.tcginventory.fetchtcg.server;

import com.jordansimsmith.service.BadRequestException;
import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.service.ServiceException;
import com.jordansimsmith.service.ServiceUnavailableException;
import com.jordansimsmith.tcginventory.fetchtcg.client.CardSearchResult;
import com.jordansimsmith.tcginventory.fetchtcg.client.FindCardsRequest;
import com.jordansimsmith.tcginventory.fetchtcg.client.FindCardsResponse;

public class FindCardsService {
  private static final int DEFAULT_LIMIT = 100;
  private static final int MAX_LIMIT = 100;

  private final HttpFetchTcgClient fetchTcgClient;

  public FindCardsService(HttpFetchTcgClient fetchTcgClient) {
    this.fetchTcgClient = fetchTcgClient;
  }

  public FindCardsResponse findCards(RequestContext ctx, FindCardsRequest request)
      throws ServiceException {
    if (request == null
        || isBlank(request.gameId())
        || isBlank(request.cardName())
        || isBlank(request.finish())) {
      throw new BadRequestException("game_id, card_name, and finish are required");
    }
    var limit = request.limit() == null ? DEFAULT_LIMIT : request.limit();
    if (limit < 1 || limit > MAX_LIMIT) {
      throw new BadRequestException("limit must be between 1 and 100");
    }
    int pageOffset;
    if (request.continuation() == null) {
      pageOffset = 0;
    } else {
      pageOffset = decodeContinuation(request.continuation());
      if (pageOffset < 1) {
        throw new BadRequestException("continuation is invalid");
      }
    }

    HttpFetchTcgClient.CardsPage page;
    try {
      page =
          fetchTcgClient.findCards(
              request.gameId(),
              request.setId(),
              request.cardName(),
              request.finish(),
              limit,
              pageOffset);
    } catch (FetchTcgAuthenticationException e) {
      throw new ServiceUnavailableException("FetchTCG card search is temporarily unavailable");
    }
    if (page.number() == null
        || page.number() != pageOffset
        || page.size() == null
        || page.size() != limit
        || page.last() == null
        || page.content() == null
        || page.content().size() > limit
        || page.content().stream()
            .anyMatch(card -> card == null || card.id() == null || card.id().isBlank())) {
      throw new IllegalStateException("FetchTCG card search response contained invalid pagination");
    }
    if (!page.last() && pageOffset == Integer.MAX_VALUE) {
      throw new IllegalStateException("FetchTCG card search exceeded the page range");
    }
    var cards = page.content().stream().map(card -> new CardSearchResult(card.id())).toList();
    var nextContinuation = page.last() ? null : encodeContinuation(pageOffset + 1);
    return new FindCardsResponse(cards, nextContinuation);
  }

  private String encodeContinuation(int pageOffset) {
    return Integer.toString(pageOffset);
  }

  private int decodeContinuation(String continuation) throws BadRequestException {
    try {
      return Integer.parseInt(continuation);
    } catch (NumberFormatException e) {
      throw new BadRequestException("continuation is invalid");
    }
  }

  private boolean isBlank(String value) {
    return value == null || value.isBlank();
  }
}
