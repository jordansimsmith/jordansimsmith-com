package com.jordansimsmith.tcginventory.pricing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.service.ServiceException;
import com.jordansimsmith.service.ServiceUnavailableException;
import com.jordansimsmith.tcginventory.Condition;
import com.jordansimsmith.tcginventory.fetchtcg.client.CardListing;
import com.jordansimsmith.tcginventory.fetchtcg.client.FakeFetchTcgService;
import com.jordansimsmith.tcginventory.fetchtcg.client.FetchTcgService;
import com.jordansimsmith.tcginventory.fetchtcg.client.FindCardListingsRequest;
import com.jordansimsmith.tcginventory.fetchtcg.client.FindCardListingsResponse;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CompetitorPricingTest {
  private final FakeFetchTcgService fetchTcgService = new FakeFetchTcgService();
  private final CompetitorPricing competitorPricing = new CompetitorPricing(fetchTcgService);
  private final RequestContext ctx = new RequestContext("jordan");

  @Test
  void findCompetitorPriceTiersShouldAggregateAndFilterEveryPage() throws ServiceException {
    // arrange
    var listings =
        new ArrayList<>(
            List.of(
                listing(1, "raw-nm", "0.40", "jordan"),
                listing(2, "raw-nm", "0.95", "alpha"),
                listing(3, "raw-nm", "0.95", "beta"),
                listing(4, "unknown", "0.25", "unknown-condition")));
    while (listings.size() < 100) {
      listings.add(listing(listings.size() + 1, "unknown", "0.25", "unknown-condition"));
    }
    listings.addAll(
        List.of(
            listing(101, "raw-lp", "0.50", "worse-condition"),
            listing(102, "raw-nm", "0.95", "alpha")));
    fetchTcgService.seedListings("card-1", listings);

    // act
    var tiers = competitorPricing.findCompetitorPriceTiers(ctx, "card-1", Condition.NM, "jordan");

    // assert
    assertThat(tiers)
        .containsExactly(
            new CompetitorPricing.CompetitorPriceTier(
                new BigDecimal("0.95"), Set.of("alpha", "beta")));
  }

  @Test
  void findCompetitorPriceTiersShouldAllowEmptyFinalPage() throws ServiceException {
    // arrange
    fetchTcgService.seedListings("card-1", List.of());

    // act
    var tiers = competitorPricing.findCompetitorPriceTiers(ctx, "card-1", Condition.LP, "jordan");

    // assert
    assertThat(tiers).isEmpty();
  }

  @Test
  void findCompetitorPriceTiersShouldContinueWhenFirstPageHasNoEligibleListings()
      throws ServiceException {
    // arrange
    var listings = new ArrayList<CardListing>();
    for (int i = 1; i <= 100; i++) {
      listings.add(listing(i, "unknown", "0.25", "unknown-condition"));
    }
    listings.add(listing(101, "raw-lp", "0.50", "light-played-seller"));
    fetchTcgService.seedListings("card-1", listings);

    // act
    var tiers = competitorPricing.findCompetitorPriceTiers(ctx, "card-1", Condition.LP, "jordan");

    // assert
    assertThat(tiers)
        .containsExactly(
            new CompetitorPricing.CompetitorPriceTier(
                new BigDecimal("0.50"), Set.of("light-played-seller")));
  }

  @Test
  void findCompetitorPriceTiersShouldPropagateLaterPageFailure() throws ServiceException {
    // arrange
    var fetchTcgService = mock(FetchTcgService.class);
    when(fetchTcgService.findCardListings(ctx, new FindCardListingsRequest("card-1", 100, null)))
        .thenReturn(
            new FindCardListingsResponse(List.of(listing(1, "raw-nm", "1.00", "alpha")), "next"));
    when(fetchTcgService.findCardListings(ctx, new FindCardListingsRequest("card-1", 100, "next")))
        .thenThrow(new ServiceUnavailableException("FetchTCG is temporarily unavailable"));

    // act
    var thrown =
        catchThrowable(
            () ->
                new CompetitorPricing(fetchTcgService)
                    .findCompetitorPriceTiers(ctx, "card-1", Condition.NM, "jordan"));

    // assert
    assertThat(thrown).isInstanceOf(ServiceUnavailableException.class);
  }

  private static CardListing listing(int id, String condition, String price, String seller) {
    return new CardListing(id, condition, new BigDecimal(price), seller);
  }
}
