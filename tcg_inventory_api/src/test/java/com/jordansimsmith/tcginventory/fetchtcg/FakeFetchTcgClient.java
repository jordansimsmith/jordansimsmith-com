package com.jordansimsmith.tcginventory.fetchtcg;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class FakeFetchTcgClient implements FetchTcgClient {
  private final Map<String, GetCardResponse> cards = new HashMap<>();
  private final Map<String, SearchCardsResponse> searchResults = new HashMap<>();
  private int searchCallCount;

  @Override
  public GetCardResponse getCard(String cardId) {
    var response = cards.get(cardId);
    if (response == null) {
      return new GetCardResponse(cardId, "Unknown", Map.of(), null);
    }
    return response;
  }

  @Override
  public SearchCardsResponse searchCards(String gameId, int setId, String cardName, String finish) {
    searchCallCount++;
    var key = gameId + "#" + setId + "#" + cardName + "#" + finish;
    var response = searchResults.get(key);
    if (response == null) {
      return new SearchCardsResponse(List.of());
    }
    return response;
  }

  public void seedCard(String cardId, GetCardResponse response) {
    cards.put(cardId, response);
  }

  public void seedSearchResult(
      String gameId, int setId, String cardName, String finish, SearchCardsResponse response) {
    searchResults.put(gameId + "#" + setId + "#" + cardName + "#" + finish, response);
  }

  public int getSearchCallCount() {
    return searchCallCount;
  }

  public void reset() {
    cards.clear();
    searchResults.clear();
    searchCallCount = 0;
  }
}
