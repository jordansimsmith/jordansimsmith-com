package com.jordansimsmith.tcginventory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class FakeFetchTcgClient implements FetchTcgClient {
  private final Map<String, GetCardResponse> cards = new HashMap<>();
  private final Map<String, SearchCardsResponse> searchResults = new HashMap<>();
  private final Map<String, GetCardListingsResponse> listings = new HashMap<>();
  private final Map<Integer, GetSellerOffersResponse> sellerOffers = new HashMap<>();
  private final List<UpsertListingRequest> upsertCalls = new ArrayList<>();
  private final List<Integer> deleteCalls = new ArrayList<>();
  private final List<UploadCall> uploadCalls = new ArrayList<>();
  private int searchCallCount;
  private int nextListingId = 900000;
  private int nextImageId = 1;

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
      return new GetSellerOffersResponse(List.of(), 0);
    }
    return response;
  }

  @Override
  public UpsertListingResponse upsertListing(String bearerToken, UpsertListingRequest request) {
    upsertCalls.add(request);
    return new UpsertListingResponse(nextListingId++, request.quantity());
  }

  @Override
  public void deleteListing(String bearerToken, int listingId) {
    deleteCalls.add(listingId);
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
      int setId, String cardName, String finish, SearchCardsResponse response) {
    searchResults.put(setId + "#" + cardName + "#" + finish, response);
  }

  public void seedListings(String cardId, GetCardListingsResponse response) {
    listings.put(cardId, response);
  }

  public void seedSellerOffers(List<SellerOffer> offers) {
    sellerOffers.put(0, new GetSellerOffersResponse(offers, 1));
  }

  public void seedSellerOffers(int page, GetSellerOffersResponse response) {
    sellerOffers.put(page, response);
  }

  public List<UpsertListingRequest> getUpsertCalls() {
    return upsertCalls;
  }

  public List<Integer> getDeleteCalls() {
    return deleteCalls;
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
    listings.clear();
    sellerOffers.clear();
    upsertCalls.clear();
    deleteCalls.clear();
    uploadCalls.clear();
    searchCallCount = 0;
    nextListingId = 900000;
    nextImageId = 1;
  }

  public record UploadCall(byte[] bytes, String filename) {}
}
