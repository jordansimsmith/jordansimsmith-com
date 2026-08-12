package com.jordansimsmith.tcginventory;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

public interface FetchTcgClient {
  GetCardResponse getCard(int cardId);

  GetSellerOffersResponse getSellerOffers(String bearerToken, int page);

  @JsonIgnoreProperties(ignoreUnknown = true)
  record GetCardResponse(
      @JsonProperty("id") int id,
      @JsonProperty("name") String name,
      @JsonProperty("pricingData") Map<String, PricingData> pricingData) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  record PricingData(@JsonProperty("tcgMarketPrice") BigDecimal tcgMarketPrice) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  record GetSellerOffersResponse(
      @JsonProperty("data") List<SellerOffer> data, @JsonProperty("totalPages") int totalPages) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  record SellerOffer(
      @JsonProperty("id") int id,
      @JsonProperty("status") String status,
      @JsonProperty("currentAction") String currentAction) {}
}
