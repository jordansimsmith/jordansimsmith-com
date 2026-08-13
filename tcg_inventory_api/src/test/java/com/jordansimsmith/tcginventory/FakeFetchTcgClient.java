package com.jordansimsmith.tcginventory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class FakeFetchTcgClient implements FetchTcgClient {
  private final Map<String, GetCardResponse> cards = new HashMap<>();
  private final Map<String, SearchCardsResponse> searchResults = new HashMap<>();
  private final Map<String, GetCardListingsResponse> listings = new HashMap<>();
  private final Map<Integer, GetSellerOffersResponse> sellerOffers = new HashMap<>();
  private int searchCallCount;

  @Override
  public GetCardResponse getCard(String cardId) {
    var response = cards.get(cardId);
    if (response == null) {
      return new GetCardResponse(cardId, "Unknown", Map.of());
    }
    return response;
  }

  @Override
  public SearchCardsResponse searchCards(int setId, String cardName, String finish) {
    searchCallCount++;
    var key = setId + "#" + cardName + "#" + finish;
    var response = searchResults.get(key);
    if (response == null) {
      return new SearchCardsResponse(List.of());
    }
    return response;
  }

  @Override
  public GetCardListingsResponse getCardListings(String cardId) {
    var response = listings.get(cardId);
    if (response == null) {
      return new GetCardListingsResponse(List.of());
    }
    return response;
  }

  @Override
  public GetSellerOffersResponse getSellerOffers(String bearerToken, int page) {
    var response = sellerOffers.get(page);
    if (response == null) {
      return new GetSellerOffersResponse(List.of(), 1);
    }
    return response;
  }

  public void seedCard(String cardId, GetCardResponse response) {
    cards.put(cardId, response);
  }

  public void seedSearchResult(
      int setId, String cardName, String finish, SearchCardsResponse response) {
    searchResults.put(setId + "#" + cardName + "#" + finish, response);
  }

  public void seedListings(String cardId, GetCardListingsResponse response) {
    listings.put(cardId, response);
  }

  public void seedSellerOffers(List<SellerOffer> offers) {
    sellerOffers.put(1, new GetSellerOffersResponse(offers, 1));
  }

  public void seedSellerOffers(int page, GetSellerOffersResponse response) {
    sellerOffers.put(page, response);
  }

  public int getSearchCallCount() {
    return searchCallCount;
  }

  public void reset() {
    cards.clear();
    searchResults.clear();
    listings.clear();
    sellerOffers.clear();
    searchCallCount = 0;
  }
}
