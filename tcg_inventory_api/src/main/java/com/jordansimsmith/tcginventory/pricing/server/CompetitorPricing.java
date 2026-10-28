package com.jordansimsmith.tcginventory.pricing.server;

import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.service.ServiceException;
import com.jordansimsmith.tcginventory.fetchtcg.client.FetchTcgService;
import com.jordansimsmith.tcginventory.fetchtcg.client.FindCardListingsRequest;
import com.jordansimsmith.tcginventory.pricing.client.Condition;
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

        var listingCondition = fromFetchTcg(listing.condition());
        if (listingCondition == null || listingCondition.ordinal() > condition.ordinal()) {
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

  private Condition fromFetchTcg(String condition) {
    if (condition == null) {
      return null;
    }
    return switch (condition) {
      case "raw-nm" -> Condition.NM;
      case "raw-lp" -> Condition.LP;
      case "raw-mp" -> Condition.MP;
      case "raw-hp" -> Condition.HP;
      case "raw-d" -> Condition.DMG;
      default -> null;
    };
  }
}
