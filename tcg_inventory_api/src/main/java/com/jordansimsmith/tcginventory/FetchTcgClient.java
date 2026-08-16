package com.jordansimsmith.tcginventory;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

public interface FetchTcgClient {
  GetCardResponse getCard(String cardId);

  SearchCardsResponse searchCards(int setId, String cardName, String finish);

  GetCardListingsResponse getCardListings(String cardId);

  GetSellerOffersResponse getSellerOffers(String bearerToken, int page);

  UpsertListingResponse upsertListing(String bearerToken, UpsertListingRequest request);

  void deleteListing(String bearerToken, int listingId);

  @JsonIgnoreProperties(ignoreUnknown = true)
  record GetCardResponse(
      @JsonProperty("id") String id,
      @JsonProperty("name") String name,
      @JsonProperty("pricingData") Map<String, PricingData> pricingData) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  record PricingData(@JsonProperty("tcgMarketPrice") BigDecimal tcgMarketPrice) {}

  record SearchCardsResponse(List<SearchCard> content) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  record SearchCard(@JsonProperty("id") String id) {}

  record GetCardListingsResponse(List<CardListing> content) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  record CardListing(
      @JsonProperty("id") int id,
      @JsonProperty("condition") String condition,
      @JsonProperty("listedPrice") BigDecimal listedPrice,
      @JsonProperty("sellerProfileName") String sellerProfileName) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  record GetSellerOffersResponse(
      @JsonProperty("content") List<SellerOffer> content,
      @JsonProperty("totalPages") int totalPages) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  record SellerOffer(
      @JsonProperty("id") int id,
      @JsonProperty("status") String status,
      @JsonProperty("currentAction") String currentAction,
      @JsonProperty("acceptedAt") String acceptedAt,
      @JsonProperty("deliveryMode") String deliveryMode,
      @JsonProperty("totalOfferPrice") BigDecimal totalOfferPrice,
      @JsonProperty("items") List<OfferItem> items) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  record OfferItem(
      @JsonProperty("listing") OfferListing listing,
      @JsonProperty("quantity") int quantity,
      @JsonProperty("price") BigDecimal price) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  record OfferListing(@JsonProperty("id") int id, @JsonProperty("condition") String condition) {}

  record UpsertListingRequest(String cardId, String condition, int quantity, BigDecimal price) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  record UpsertListingResponse(
      @JsonProperty("listingId") int listingId,
      @JsonProperty("remainingQuantity") int remainingQuantity) {}
}
