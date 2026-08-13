package com.jordansimsmith.tcginventory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class FakeFetchTcgClient implements FetchTcgClient {
  private final Map<Integer, GetCardResponse> cards = new HashMap<>();
  private final Map<String, SearchCardsResponse> searchResults = new HashMap<>();
  private final Map<Integer, GetCardListingsResponse> listings = new HashMap<>();
  private int searchCallCount;

  @Override
  public GetCardResponse getCard(int cardId) {
    var response = cards.get(cardId);
    if (response == null) {
      return new GetCardResponse(cardId, "Unknown", Map.of());
    }
    return response;
  }

  @Override
  public SearchCardsResponse searchCards(int setId, String collectorNumber) {
    searchCallCount++;
    var key = setId + "#" + collectorNumber;
    var response = searchResults.get(key);
    if (response == null) {
      return new SearchCardsResponse(List.of());
    }
    return response;
  }

  @Override
  public GetCardListingsResponse getCardListings(int cardId) {
    var response = listings.get(cardId);
    if (response == null) {
      return new GetCardListingsResponse(List.of());
    }
    return response;
  }

  @Override
  public GetSellerOffersResponse getSellerOffers(String bearerToken, int page) {
    return new GetSellerOffersResponse(List.of(), 0);
  }

  public void seedCard(int cardId, GetCardResponse response) {
    cards.put(cardId, response);
  }

  public void seedSearchResult(int setId, String collectorNumber, SearchCardsResponse response) {
    searchResults.put(setId + "#" + collectorNumber, response);
  }

  public void seedListings(int cardId, GetCardListingsResponse response) {
    listings.put(cardId, response);
  }

  public int getSearchCallCount() {
    return searchCallCount;
  }

  public void reset() {
    cards.clear();
    searchResults.clear();
    listings.clear();
    searchCallCount = 0;
  }
}
