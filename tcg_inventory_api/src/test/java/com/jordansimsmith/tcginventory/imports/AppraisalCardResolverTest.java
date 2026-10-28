package com.jordansimsmith.tcginventory.imports;

import static org.assertj.core.api.Assertions.assertThat;

import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.tcginventory.fetchtcg.client.CardSearchResult;
import com.jordansimsmith.tcginventory.fetchtcg.client.FakeFetchTcgService;
import com.jordansimsmith.tcginventory.fetchtcg.client.GetCardResponse;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AppraisalCardResolverTest {
  private final RequestContext ctx = new RequestContext("alice");

  @Test
  void resolveShouldReturnCardWithExactExternalReference() throws Exception {
    // arrange
    var fetchTcgService = new FakeFetchTcgService();
    fetchTcgService.seedSearchResult(
        "mtg", 42, "Lightning Bolt", "normal", List.of(new CardSearchResult("card-1")));
    fetchTcgService.seedCard(
        "card-1",
        new GetCardResponse("card-1", "Lightning Bolt", null, Map.of("scryfallId", "printing")));
    var cardCache = new HashMap<String, GetCardResponse>();

    // act
    var result =
        AppraisalCardResolver.resolve(
            "mtg",
            List.of(42),
            "Lightning Bolt",
            "normal",
            "scryfallId",
            "printing",
            ctx,
            fetchTcgService,
            cardCache);

    // assert
    assertThat(result).contains(new AppraisalCatalogs.AppraisalCatalog.ResolvedCard("card-1", 42));
    assertThat(cardCache).containsKey("card-1");
  }

  @Test
  void resolveShouldRejectNonExactReferences() throws Exception {
    // arrange
    var fetchTcgService = new FakeFetchTcgService();
    fetchTcgService.seedSearchResult(
        "mtg", 42, "Lightning Bolt", "normal", List.of(new CardSearchResult("card-1")));
    fetchTcgService.seedCard(
        "card-1",
        new GetCardResponse("card-1", "Lightning Bolt", null, Map.of("scryfallId", "other")));

    // act
    var result =
        AppraisalCardResolver.resolve(
            "mtg",
            List.of(42),
            "Lightning Bolt",
            "normal",
            "scryfallId",
            "printing",
            ctx,
            fetchTcgService,
            new HashMap<>());

    // assert
    assertThat(result).isEmpty();
  }

  @Test
  void resolveShouldTreatSameCardInMultipleSetsAsOneMatch() throws Exception {
    // arrange
    var fetchTcgService = new FakeFetchTcgService();
    var card = List.of(new CardSearchResult("card-1"));
    fetchTcgService.seedSearchResult("mtg", 42, "Lightning Bolt", "foil", card);
    fetchTcgService.seedSearchResult("mtg", 43, "Lightning Bolt", "foil", card);
    fetchTcgService.seedCard(
        "card-1",
        new GetCardResponse("card-1", "Lightning Bolt", null, Map.of("scryfallId", "printing")));

    // act
    var result =
        AppraisalCardResolver.resolve(
            "mtg",
            List.of(42, 43),
            "Lightning Bolt",
            "foil",
            "scryfallId",
            "printing",
            ctx,
            fetchTcgService,
            new HashMap<>());

    // assert
    assertThat(result).contains(new AppraisalCatalogs.AppraisalCatalog.ResolvedCard("card-1", 42));
  }

  @Test
  void resolveShouldFindMatchAfterFirstSearchPage() throws Exception {
    // arrange
    var fetchTcgService = new FakeFetchTcgService();
    var results = new ArrayList<CardSearchResult>();
    for (int i = 0; i < 100; i++) {
      results.add(new CardSearchResult("card-" + i));
    }
    results.add(new CardSearchResult("matching-card"));
    fetchTcgService.seedSearchResult("mtg", 42, "Lightning Bolt", "normal", results);
    fetchTcgService.seedCard(
        "matching-card",
        new GetCardResponse(
            "matching-card", "Lightning Bolt", null, Map.of("scryfallId", "printing")));

    // act
    var result =
        AppraisalCardResolver.resolve(
            "mtg",
            List.of(42),
            "Lightning Bolt",
            "normal",
            "scryfallId",
            "printing",
            ctx,
            fetchTcgService,
            new HashMap<>());

    // assert
    assertThat(result)
        .contains(new AppraisalCatalogs.AppraisalCatalog.ResolvedCard("matching-card", 42));
  }
}
