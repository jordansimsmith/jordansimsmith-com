package com.jordansimsmith.tcginventory.pricing;

import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.service.ServiceException;
import com.jordansimsmith.tcginventory.Condition;
import com.jordansimsmith.tcginventory.fetchtcg.client.FetchTcgService;
import com.jordansimsmith.tcginventory.fetchtcg.client.FindCardListingsRequest;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;

public class CompetitorPricing {
  public record CompetitorPriceTier(BigDecimal price, Set<String> sellerKeys) {}

  private final FetchTcgService fetchTcgService;

  public CompetitorPricing(FetchTcgService fetchTcgService) {
    this.fetchTcgService = fetchTcgService;
  }

  public List<CompetitorPriceTier> findCompetitorPriceTiers(
      RequestContext ctx, String cardId, Condition condition, String sellerUsername)
      throws ServiceException {
    var priceToSellers = new TreeMap<BigDecimal, Set<String>>();
    String continuation = null;
    do {
      var response =
          fetchTcgService.findCardListings(
              ctx, new FindCardListingsRequest(cardId, 100, continuation));
      for (var listing : response.listings()) {
        if (sellerUsername.equals(listing.sellerUsername())) {
          continue;
        }

        var listingCondition = Condition.fromFetchtcg(listing.condition());
        if (listingCondition == null || !listingCondition.isSameOrBetterThan(condition)) {
          continue;
        }
        priceToSellers
            .computeIfAbsent(listing.listedPrice(), ignored -> new HashSet<>())
            .add(listing.sellerUsername());
      }
      continuation = response.nextContinuation();
    } while (continuation != null);

    var tiers = new ArrayList<CompetitorPriceTier>();
    for (var entry : priceToSellers.entrySet()) {
      tiers.add(new CompetitorPriceTier(entry.getKey(), entry.getValue()));
    }
    return tiers;
  }
}
