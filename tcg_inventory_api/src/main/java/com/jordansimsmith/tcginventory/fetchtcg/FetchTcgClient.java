package com.jordansimsmith.tcginventory.fetchtcg;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;

public interface FetchTcgClient {
  GetCardResponse getCard(String cardId);

  SearchCardsResponse searchCards(String fetchTcgGameId, int setId, String cardName, String finish);

  GetCardListingsResponse getCardListings(String cardId);

  GetSellerOffersResponse getSellerOffers(String bearerToken, int page);

  UpsertListingResponse upsertListing(String bearerToken, UpsertListingRequest request);

  void deleteListing(String bearerToken, int listingId);

  String uploadListingImage(String bearerToken, byte[] bytes, String filename);

  @JsonIgnoreProperties(ignoreUnknown = true)
  record GetCardResponse(
      @JsonProperty("id") String id,
      @JsonProperty("name") String name,
      @JsonProperty("pricingData") Map<String, PricingData> pricingData,
      @JsonProperty("externalReferences") Map<String, String> externalReferences) {}

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
      @JsonProperty("buyerName") @Nullable String buyerName,
      @JsonProperty("buyerRegionAddress") @Nullable BuyerRegionAddress buyerRegionAddress,
      @JsonProperty("shippingOption") @Nullable ShippingOption shippingOption,
      @JsonProperty("totalOfferPrice") BigDecimal totalOfferPrice,
      @JsonProperty("items") List<OfferItem> items) {}

  // deliberately narrow: the offer payload also carries geocoordinates and pickup flags that the
  // service must not persist
  @JsonIgnoreProperties(ignoreUnknown = true)
  record BuyerRegionAddress(
      @JsonProperty("line1") @Nullable String line1,
      @JsonProperty("line2") @Nullable String line2,
      @JsonProperty("suburb") @Nullable String suburb,
      @JsonProperty("city") @Nullable String city,
      @JsonProperty("postCode") @Nullable String postCode,
      @JsonProperty("country") @Nullable String country) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  record ShippingOption(@JsonProperty("title") @Nullable String title) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  record OfferItem(
      @JsonProperty("listing") OfferListing listing,
      @JsonProperty("quantity") int quantity,
      @JsonProperty("price") BigDecimal price) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  record OfferListing(
      @JsonProperty("id") int id,
      @JsonProperty("condition") String condition,
      @JsonProperty("listedPrice") @Nullable BigDecimal listedPrice) {}

  record UpsertListingRequest(
      String cardId,
      String condition,
      int quantity,
      BigDecimal price,
      @Nullable String frontImage,
      @Nullable List<String> additionalImages) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  record UpsertListingResponse(
      @JsonProperty("listingId") int listingId,
      @JsonProperty("remainingQuantity") int remainingQuantity) {}
}
