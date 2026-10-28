package com.jordansimsmith.tcginventory.fetchtcg;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class FakeFetchTcgClient implements FetchTcgClient {
  private final Map<String, GetCardResponse> cards = new HashMap<>();
  private final Map<String, SearchCardsResponse> searchResults = new HashMap<>();
  private final List<UploadCall> uploadCalls = new ArrayList<>();
  private int searchCallCount;
  private int nextImageId = 1;

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

  @Override
  public String uploadListingImage(String bearerToken, byte[] bytes, String filename) {
    uploadCalls.add(new UploadCall(bytes, filename));
    return "https://listing-img.fetchtcg.com/fake/listing/" + nextImageId++ + ".jpg";
  }

  public void seedCard(String cardId, GetCardResponse response) {
    cards.put(cardId, response);
  }

  public void seedSearchResult(
      String gameId, int setId, String cardName, String finish, SearchCardsResponse response) {
    searchResults.put(gameId + "#" + setId + "#" + cardName + "#" + finish, response);
  }

  public List<UploadCall> getUploadCalls() {
    return uploadCalls;
  }

  public int getSearchCallCount() {
    return searchCallCount;
  }

  public void reset() {
    cards.clear();
    searchResults.clear();
    uploadCalls.clear();
    searchCallCount = 0;
    nextImageId = 1;
  }

  public record UploadCall(byte[] bytes, String filename) {}
}
