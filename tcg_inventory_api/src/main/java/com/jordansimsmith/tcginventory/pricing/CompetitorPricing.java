package com.jordansimsmith.tcginventory.pricing;

import com.jordansimsmith.tcginventory.Condition;
import com.jordansimsmith.tcginventory.fetchtcg.FetchTcgClient;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;

public class CompetitorPricing {
  public record CompetitorPriceTier(BigDecimal price, Set<String> sellerKeys) {}

  private final FetchTcgClient fetchTcgClient;

  public CompetitorPricing(FetchTcgClient fetchTcgClient) {
    this.fetchTcgClient = fetchTcgClient;
  }

  public List<CompetitorPriceTier> findCompetitorPriceTiers(
      String cardId, Condition condition, String sellerUsername) {
    var listingsResponse = fetchTcgClient.getCardListings(cardId);
    var priceToSellers = new TreeMap<BigDecimal, Set<String>>();
    for (var listing : listingsResponse.content()) {
      if (sellerUsername.equals(listing.sellerProfileName())) {
        continue;
      }

      var listingCondition = Condition.fromFetchtcg(listing.condition());
      if (listingCondition == null || !listingCondition.isSameOrBetterThan(condition)) {
        continue;
      }
      priceToSellers
          .computeIfAbsent(listing.listedPrice(), ignored -> new HashSet<>())
          .add(listing.sellerProfileName());
    }

    var tiers = new ArrayList<CompetitorPriceTier>();
    for (var entry : priceToSellers.entrySet()) {
      tiers.add(new CompetitorPriceTier(entry.getKey(), entry.getValue()));
    }
    return tiers;
  }
}
