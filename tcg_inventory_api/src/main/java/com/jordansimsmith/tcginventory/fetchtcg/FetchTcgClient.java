package com.jordansimsmith.tcginventory.fetchtcg;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

public interface FetchTcgClient {
  GetCardResponse getCard(String cardId);

  SearchCardsResponse searchCards(String fetchTcgGameId, int setId, String cardName, String finish);

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
}
