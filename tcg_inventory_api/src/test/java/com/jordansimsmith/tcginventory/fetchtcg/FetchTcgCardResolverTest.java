package com.jordansimsmith.tcginventory.fetchtcg;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FetchTcgCardResolverTest {
  @Test
  void resolveShouldReturnResolvedCardWhenCandidateHasExactExternalReference() {
    // arrange
    var fetchTcgClient = new FakeFetchTcgClient();
    fetchTcgClient.seedSearchResult(
        "mtg",
        42,
        "Lightning Bolt",
        "normal",
        new FetchTcgClient.SearchCardsResponse(List.of(new FetchTcgClient.SearchCard("card-1"))));
    fetchTcgClient.seedCard(
        "card-1",
        new FetchTcgClient.GetCardResponse(
            "card-1",
            "Lightning Bolt",
            Map.of("NZ", new FetchTcgClient.PricingData(new BigDecimal("1.236"))),
            Map.of("scryfallId", "opaque-id")));

    // act
    var result =
        FetchTcgCardResolver.resolve(
            "mtg",
            List.of(42),
            "Lightning Bolt",
            "normal",
            "scryfallId",
            "opaque-id",
            fetchTcgClient,
            new HashMap<>());

    // assert
    assertThat(result)
        .contains(new FetchTcgCardResolver.ResolvedCard("card-1", 42, new BigDecimal("1.24")));
  }

  @Test
  void resolveShouldSkipCandidateWhenExternalReferenceDoesNotMatchExactly() {
    // arrange
    var fetchTcgClient = new FakeFetchTcgClient();
    fetchTcgClient.seedSearchResult(
        "mtg",
        42,
        "Lightning Bolt",
        "normal",
        new FetchTcgClient.SearchCardsResponse(List.of(new FetchTcgClient.SearchCard("card-1"))));
    fetchTcgClient.seedCard(
        "card-1",
        new FetchTcgClient.GetCardResponse(
            "card-1", "Lightning Bolt", Map.of(), Map.of("scryfallId", "OPAQUE-ID")));

    // act
    var result =
        FetchTcgCardResolver.resolve(
            "mtg",
            List.of(42),
            "Lightning Bolt",
            "normal",
            "scryfallId",
            "opaque-id",
            fetchTcgClient,
            new HashMap<>());

    // assert
    assertThat(result).isEmpty();
  }
}
