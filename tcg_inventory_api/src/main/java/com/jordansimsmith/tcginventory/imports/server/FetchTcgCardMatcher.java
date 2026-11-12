package com.jordansimsmith.tcginventory.imports.server;

import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.service.ServiceException;
import com.jordansimsmith.tcginventory.fetchtcg.client.FetchTcgService;
import com.jordansimsmith.tcginventory.fetchtcg.client.FindCardsRequest;
import com.jordansimsmith.tcginventory.fetchtcg.client.GetCardRequest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class FetchTcgCardMatcher {
  private static final int BATCH_SIZE = 100;
  private static final String FETCHTCG_EXTERNAL_REFERENCE_FIELD = "scryfallId";

  private final FetchTcgService fetchTcgService;

  public FetchTcgCardMatcher(FetchTcgService fetchTcgService) {
    this.fetchTcgService = fetchTcgService;
  }

  public FetchTcgCardMatch findMatch(
      RequestContext ctx,
      String game,
      List<Integer> setIds,
      String searchName,
      String finish,
      String externalId)
      throws ServiceException {
    var candidates = new ArrayList<Map.Entry<String, Integer>>();
    for (var setId : setIds) {
      candidates.addAll(findCards(ctx, game, setId, searchName, finish));
    }

    var matchingCards = new LinkedHashMap<String, Integer>();
    var seenCardIds = new HashSet<String>();
    for (var candidate : candidates) {
      var cardId = candidate.getKey();
      if (!seenCardIds.add(cardId)) {
        continue;
      }
      var externalReferences =
          fetchTcgService.getCard(ctx, new GetCardRequest(cardId)).externalReferences();
      if (externalReferences != null
          && externalId.equals(externalReferences.get(FETCHTCG_EXTERNAL_REFERENCE_FIELD))) {
        matchingCards.put(cardId, candidate.getValue());
      }
    }
    if (matchingCards.isEmpty()) {
      return new FetchTcgCardMatch(null, null, "unresolvable");
    }
    if (matchingCards.size() > 1) {
      return new FetchTcgCardMatch(null, null, "ambiguous match");
    }
    Map.Entry<String, Integer> match = matchingCards.entrySet().iterator().next();
    return new FetchTcgCardMatch(match.getKey(), match.getValue(), null);
  }

  private List<Map.Entry<String, Integer>> findCards(
      RequestContext ctx, String game, int setId, String searchName, String finish)
      throws ServiceException {
    var cards = new ArrayList<Map.Entry<String, Integer>>();
    String continuation = null;
    do {
      var response =
          fetchTcgService.findCards(
              ctx, new FindCardsRequest(game, setId, searchName, finish, BATCH_SIZE, continuation));
      cards.addAll(response.cards().stream().map(card -> Map.entry(card.id(), setId)).toList());
      continuation = response.nextContinuation();
    } while (continuation != null);
    return cards;
  }
}
