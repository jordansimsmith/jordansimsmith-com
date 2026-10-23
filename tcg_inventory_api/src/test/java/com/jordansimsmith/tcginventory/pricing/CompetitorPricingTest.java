package com.jordansimsmith.tcginventory.pricing;

import static org.assertj.core.api.Assertions.assertThat;

import com.jordansimsmith.tcginventory.Condition;
import com.jordansimsmith.tcginventory.fetchtcg.FakeFetchTcgClient;
import com.jordansimsmith.tcginventory.fetchtcg.FetchTcgClient;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CompetitorPricingTest {
  @Test
  void findCompetitorPriceTiersReturnsNoTiersWhenEveryListingBelongsToTheSeller() {
    var client = new FakeFetchTcgClient();
    client.seedListings(
        "card-1",
        new FetchTcgClient.GetCardListingsResponse(
            List.of(
                listing(1, "raw-nm", "0.40", "jordan"), listing(2, "raw-lp", "0.50", "jordan"))));

    var tiers =
        new CompetitorPricing(client).findCompetitorPriceTiers("card-1", Condition.LP, "jordan");

    assertThat(tiers).isEmpty();
  }

  @Test
  void findCompetitorPriceTiersExcludesOwnListingsAndKeepsComparableCompetitors() {
    var client = new FakeFetchTcgClient();
    client.seedListings(
        "card-1",
        new FetchTcgClient.GetCardListingsResponse(
            List.of(
                listing(1, "raw-nm", "0.40", "jordan"),
                listing(2, "raw-nm", "0.95", "rival1"),
                listing(3, "raw-nm", "0.95", "rival2"),
                listing(4, "raw-lp", "0.50", "worse-condition"))));
    var competitorPricing = new CompetitorPricing(client);

    var tiers = competitorPricing.findCompetitorPriceTiers("card-1", Condition.NM, "jordan");

    assertThat(tiers)
        .containsExactly(
            new CompetitorPricing.CompetitorPriceTier(
                new BigDecimal("0.95"), Set.of("rival1", "rival2")));
  }

  @Test
  void findCompetitorPriceTiersIncludesSameAndBetterConditionListings() {
    var client = new FakeFetchTcgClient();
    client.seedListings(
        "card-1",
        new FetchTcgClient.GetCardListingsResponse(
            List.of(
                listing(1, "raw-nm", "0.75", "near-mint-seller"),
                listing(2, "raw-lp", "0.50", "light-played-seller"))));

    var tiers =
        new CompetitorPricing(client).findCompetitorPriceTiers("card-1", Condition.LP, "jordan");

    assertThat(tiers)
        .containsExactly(
            new CompetitorPricing.CompetitorPriceTier(
                new BigDecimal("0.50"), Set.of("light-played-seller")),
            new CompetitorPricing.CompetitorPriceTier(
                new BigDecimal("0.75"), Set.of("near-mint-seller")));
  }

  private static FetchTcgClient.CardListing listing(
      int id, String condition, String price, String seller) {
    return new FetchTcgClient.CardListing(id, condition, new BigDecimal(price), seller);
  }
}
