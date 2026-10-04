package com.jordansimsmith.tcginventory.fetchtcg;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class FetchTcgCardResolver {
  public record ResolvedCard(String cardId, int setId, BigDecimal marketPrice) {}

  public static Optional<ResolvedCard> resolve(
      String fetchTcgGameId,
      List<Integer> setIds,
      String searchName,
      String finish,
      String externalReferenceField,
      String externalId,
      FetchTcgClient fetchTcgClient,
      Map<String, FetchTcgClient.GetCardResponse> cardCache) {
    Map<String, Integer> matchingCardSetIds = new LinkedHashMap<>();
    for (var setId : setIds) {
      var searchResult = fetchTcgClient.searchCards(fetchTcgGameId, setId, searchName, finish);
      for (var card : searchResult.content()) {
        var cardDetails = cardCache.computeIfAbsent(card.id(), fetchTcgClient::getCard);
        var externalReferences = cardDetails.externalReferences();
        if (externalReferences == null
            || !externalId.equals(externalReferences.get(externalReferenceField))) {
          continue;
        }
        matchingCardSetIds.putIfAbsent(card.id(), setId);
      }
    }
    if (matchingCardSetIds.size() != 1) {
      return Optional.empty();
    }

    var match = matchingCardSetIds.entrySet().iterator().next();
    var cardId = match.getKey();
    var matchingSetId = match.getValue();
    var pricingData = cardCache.get(cardId).pricingData();
    var nzPricing = pricingData != null ? pricingData.get("NZ") : null;
    var marketPrice =
        nzPricing != null && nzPricing.tcgMarketPrice() != null
            ? nzPricing.tcgMarketPrice()
            : BigDecimal.ZERO;
    return Optional.of(
        new ResolvedCard(cardId, matchingSetId, marketPrice.setScale(2, RoundingMode.HALF_UP)));
  }
}
