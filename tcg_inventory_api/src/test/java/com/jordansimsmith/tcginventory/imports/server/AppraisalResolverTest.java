package com.jordansimsmith.tcginventory.imports.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.tcginventory.fetchtcg.client.CardSearchResult;
import com.jordansimsmith.tcginventory.fetchtcg.client.CardSet;
import com.jordansimsmith.tcginventory.fetchtcg.client.FakeFetchTcgService;
import com.jordansimsmith.tcginventory.fetchtcg.client.GetCardResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AppraisalResolverTest {
  private static final RequestContext CTX = new RequestContext("resolver-user");

  @Test
  void resolverShouldMatchExactOpaqueIdAcrossPagesAndPreserveEtchedFinish() throws Exception {
    var fetchTcgService = new FakeFetchTcgService();
    fetchTcgService.seedSets("mtg", "opaque-set", List.of(new CardSet(42, "Set")));
    var results = new ArrayList<CardSearchResult>();
    for (int i = 0; i < 100; i++) {
      results.add(new CardSearchResult("candidate-" + i));
      fetchTcgService.seedCard(
          "candidate-" + i,
          new GetCardResponse("candidate-" + i, "Cafe", null, Map.of("scryfallId", "other-" + i)));
    }
    results.add(new CardSearchResult("resolved/card:opaque"));
    fetchTcgService.seedCard(
        "resolved/card:opaque",
        new GetCardResponse(
            "resolved/card:opaque", "Cafe", null, Map.of("scryfallId", "printing/opaque")));
    fetchTcgService.seedSearchResult("mtg", 42, "Cafe", "foil", results);
    var resolver = resolver(fetchTcgService);
    var row = row("Café // Aftermath", "etched", "printing/opaque", "en");
    row.setSetCode("opaque-set");

    var result = resolver.resolve(CTX, row);

    assertThat(result.decision()).isEqualTo(AppraisalResult.Decision.RESOLVED);
    assertThat(result.notes()).isNull();
    assertThat(result.cardId()).isEqualTo("resolved/card:opaque");
    assertThat(result.setId()).isEqualTo(42);
  }

  @Test
  void resolverShouldReturnReviewForNonEnglishCardsWithoutProviderCalls() throws Exception {
    var resolver = resolver(new FakeFetchTcgService());

    var result = resolver.resolve(CTX, row("Lightning Bolt", "normal", "id", "ja"));

    assertThat(result.decision()).isEqualTo(AppraisalResult.Decision.REVIEW);
    assertThat(result.notes()).isEqualTo("non-english");
    assertThat(result.cardId()).isNull();
    assertThat(result.setId()).isNull();
  }

  @Test
  void resolverShouldTreatSameCardFoundInMultipleSetsAsOneMatch() throws Exception {
    // arrange
    var fetchTcgService = new FakeFetchTcgService();
    fetchTcgService.seedSets(
        "mtg", "multi-set", List.of(new CardSet(42, "First Set"), new CardSet(43, "Second Set")));
    var resultCard = new CardSearchResult("shared-card");
    fetchTcgService.seedSearchResult("mtg", 42, "Lightning Bolt", "foil", List.of(resultCard));
    fetchTcgService.seedSearchResult("mtg", 43, "Lightning Bolt", "foil", List.of(resultCard));
    fetchTcgService.seedCard(
        "shared-card",
        new GetCardResponse(
            "shared-card", "Lightning Bolt", null, Map.of("scryfallId", "printing")));
    var row = row("Lightning Bolt", "foil", "printing", "en");
    row.setSetCode("multi-set");

    // act
    var result = resolver(fetchTcgService).resolve(CTX, row);

    // assert
    assertThat(result.decision()).isEqualTo(AppraisalResult.Decision.RESOLVED);
    assertThat(result.cardId()).isEqualTo("shared-card");
    assertThat(result.setId()).isEqualTo(42);
  }

  @Test
  void resolverShouldReturnReviewForAnUnmappedSet() throws Exception {
    // arrange
    var fetchTcgService = new FakeFetchTcgService();
    fetchTcgService.seedSets("mtg", "unmapped-set", List.of());
    var row = row("Lightning Bolt", "normal", "printing", "en");
    row.setSetCode("unmapped-set");

    // act
    var result = resolver(fetchTcgService).resolve(CTX, row);

    // assert
    assertThat(result.decision()).isEqualTo(AppraisalResult.Decision.REVIEW);
    assertThat(result.notes()).isEqualTo("unmapped set");
    assertThat(result.cardId()).isNull();
    assertThat(result.setId()).isNull();
  }

  @Test
  void cardMatcherUsesTheProvidedGame() throws Exception {
    var fetchTcgService = new FakeFetchTcgService();
    fetchTcgService.seedSearchResult(
        "other-game", 42, "Pikachu", "normal", List.of(new CardSearchResult("opaque/card")));
    fetchTcgService.seedCard(
        "opaque/card",
        new GetCardResponse("opaque/card", "Pikachu", null, Map.of("scryfallId", "external/id")));

    var match =
        new FetchTcgCardMatcher(fetchTcgService)
            .findMatch(CTX, "other-game", List.of(42), "Pikachu", "normal", "external/id");

    assertThat(match.cardId()).isEqualTo("opaque/card");
    assertThat(match.setId()).isEqualTo(42);
    assertThat(match.reason()).isNull();
  }

  @Test
  void resolverShouldReturnSeparateMessagesForMissingAndAmbiguousMatches() throws Exception {
    var missingFetch = fetchWithCandidates(List.of("wrong"));
    missingFetch.seedCard(
        "wrong",
        new GetCardResponse("wrong", "Lightning Bolt", null, Map.of("scryfallId", "other")));
    var missing =
        resolver(missingFetch).resolve(CTX, row("Lightning Bolt", "normal", "printing", "en"));
    var ambiguousFetch = fetchWithCandidates(List.of("first", "second"));
    var ambiguous =
        resolver(ambiguousFetch).resolve(CTX, row("Lightning Bolt", "normal", "printing", "en"));

    assertThat(missing.decision()).isEqualTo(AppraisalResult.Decision.REVIEW);
    assertThat(missing.notes()).isEqualTo("unresolvable");
    assertThat(ambiguous.decision()).isEqualTo(AppraisalResult.Decision.REVIEW);
    assertThat(ambiguous.notes()).isEqualTo("ambiguous match");
  }

  private static FakeFetchTcgService fetchWithCandidates(List<String> ids) {
    var service = new FakeFetchTcgService();
    service.seedSets("mtg", "set", List.of(new CardSet(42, "Set")));
    service.seedSearchResult(
        "mtg", 42, "Lightning Bolt", "normal", ids.stream().map(CardSearchResult::new).toList());
    ids.forEach(
        id ->
            service.seedCard(
                id,
                new GetCardResponse(id, "Lightning Bolt", null, Map.of("scryfallId", "printing"))));
    return service;
  }

  private static MagicTheGatheringAppraisalResolver resolver(FakeFetchTcgService fetchTcgService) {
    return new MagicTheGatheringAppraisalResolver(
        fetchTcgService, new FetchTcgCardMatcher(fetchTcgService));
  }

  private static ImportRowItem row(String name, String finish, String externalId, String language) {
    var row = new ImportRowItem();
    row.setName(name);
    row.setSetCode("set");
    row.setFinish(finish);
    row.setCondition("NM");
    row.setExternalId(externalId);
    row.setLanguage(language);
    return row;
  }
}
