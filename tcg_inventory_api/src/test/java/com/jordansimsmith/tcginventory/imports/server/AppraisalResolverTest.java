package com.jordansimsmith.tcginventory.imports.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.tcginventory.fetchtcg.client.CardSearchResult;
import com.jordansimsmith.tcginventory.fetchtcg.client.CardSet;
import com.jordansimsmith.tcginventory.fetchtcg.client.FakeFetchTcgService;
import com.jordansimsmith.tcginventory.fetchtcg.client.GetCardResponse;
import java.math.BigDecimal;
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
    var row = row("Café // Aftermath", "etched", "printing/opaque");
    row.setSetCode("opaque-set");

    var result = resolver.resolve(CTX, row);

    assertThat(result.decision()).isEqualTo(AppraisalResult.Decision.RESOLVED);
    assertThat(result.notes()).isNull();
    assertThat(result.cardId()).isEqualTo("resolved/card:opaque");
    assertThat(result.setId()).isEqualTo(42);
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
    var row = row("Lightning Bolt", "foil", "printing");
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
    var row = row("Lightning Bolt", "normal", "printing");
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
            .findMatch(
                CTX, "other-game", List.of(42), "Pikachu", "normal", "scryfallId", "external/id");

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
    var missing = resolver(missingFetch).resolve(CTX, row("Lightning Bolt", "normal", "printing"));
    var ambiguousFetch = fetchWithCandidates(List.of("first", "second"));
    var ambiguous =
        resolver(ambiguousFetch).resolve(CTX, row("Lightning Bolt", "normal", "printing"));

    assertThat(missing.decision()).isEqualTo(AppraisalResult.Decision.REVIEW);
    assertThat(missing.notes()).isEqualTo("unresolvable");
    assertThat(ambiguous.decision()).isEqualTo(AppraisalResult.Decision.REVIEW);
    assertThat(ambiguous.notes()).isEqualTo("ambiguous match");
  }

  @Test
  void pokemonResolverShouldResolveExactCharizardForEachFinish() throws Exception {
    // arrange
    var fetchTcgService = new FakeFetchTcgService();
    fetchTcgService.seedSets("poke", "SWSH04", List.of(new CardSet(90, "Vivid Voltage")));
    fetchTcgService.seedSets("poke", "EVO", List.of(new CardSet(112, "Evolutions")));
    var normal = "poke_vivvol-025:185_R_standard_normal";
    var reverse = "poke_vivvol-025:185_R_standard_rev-holo";
    var holo = "poke_evolut-11:108_HR_standard_holo";
    var prerelease = "poke_evolut-11:108_HR_PRE_foil";
    fetchTcgService.seedSearchResult(
        "poke", 90, "Charizard", "normal", List.of(new CardSearchResult(normal)));
    fetchTcgService.seedSearchResult(
        "poke", 90, "Charizard", "rev-holo", List.of(new CardSearchResult(reverse)));
    fetchTcgService.seedSearchResult(
        "poke",
        112,
        "Charizard",
        "holo",
        List.of(new CardSearchResult(prerelease), new CardSearchResult(holo)));
    fetchTcgService.seedCard(
        normal,
        new GetCardResponse(
            normal, "Charizard", new BigDecimal("6.3151865845"), Map.of("tcgId", "226395")));
    fetchTcgService.seedCard(
        reverse,
        new GetCardResponse(
            reverse, "Charizard", new BigDecimal("7.2199411095"), Map.of("tcgId", "226395")));
    fetchTcgService.seedCard(
        holo,
        new GetCardResponse(
            holo, "Charizard", new BigDecimal("170.7995592295"), Map.of("tcgId", "124026")));
    fetchTcgService.seedCard(
        prerelease,
        new GetCardResponse(
            prerelease,
            "Charizard (Prerelease)",
            new BigDecimal("868.5462489095"),
            Map.of("tcgId", "126022")));
    var normalRow = row("Charizard", "normal", "226395");
    normalRow.setSetCode("SWSH04");
    var reverseRow = row("Charizard", "reverse_holofoil", "226395");
    reverseRow.setSetCode("SWSH04");
    var holoRow = row("Charizard", "holofoil", "124026");
    holoRow.setSetCode("EVO");
    var resolver = pokemonResolver(fetchTcgService);

    // act
    var normalResult = resolver.resolve(CTX, normalRow);
    var reverseResult = resolver.resolve(CTX, reverseRow);
    var holoResult = resolver.resolve(CTX, holoRow);

    // assert
    assertThat(normalResult)
        .isEqualTo(new AppraisalResult(AppraisalResult.Decision.RESOLVED, null, normal, 90));
    assertThat(reverseResult)
        .isEqualTo(new AppraisalResult(AppraisalResult.Decision.RESOLVED, null, reverse, 90));
    assertThat(holoResult)
        .isEqualTo(new AppraisalResult(AppraisalResult.Decision.RESOLVED, null, holo, 112));
  }

  @Test
  void pokemonResolverShouldPreserveCatalogNameAndDeduplicateMatchesAcrossPagesAndSets()
      throws Exception {
    // arrange
    var fetchTcgService = new FakeFetchTcgService();
    fetchTcgService.seedSets(
        "poke", "MEW", List.of(new CardSet(379, "151"), new CardSet(380, "Other set")));
    var name = "Squirtle - 007/165";
    var match = new CardSearchResult("poke_sv151-007:165_C_standard_normal");
    var candidates = new ArrayList<CardSearchResult>();
    for (var i = 0; i < 100; i++) {
      candidates.add(new CardSearchResult("candidate-" + i));
    }
    candidates.add(match);
    fetchTcgService.seedSearchResult("poke", 379, name, "normal", candidates);
    fetchTcgService.seedSearchResult("poke", 380, name, "normal", List.of(match));
    fetchTcgService.seedCard(
        match.id(), new GetCardResponse(match.id(), "Squirtle", null, Map.of("tcgId", "502548")));
    var row = row(name, "normal", "502548");
    row.setSetCode("MEW");

    // act
    var result = pokemonResolver(fetchTcgService).resolve(CTX, row);

    // assert
    assertThat(result)
        .isEqualTo(new AppraisalResult(AppraisalResult.Decision.RESOLVED, null, match.id(), 379));
  }

  @Test
  void pokemonResolverShouldReturnReviewWhenExactPrintingCannotBeDetermined() throws Exception {
    // arrange
    var fetchTcgService = new FakeFetchTcgService();
    fetchTcgService.seedSets("poke", "MEW", List.of(new CardSet(379, "151")));
    fetchTcgService.seedSearchResult(
        "poke",
        379,
        "Squirtle",
        "normal",
        List.of(new CardSearchResult("first"), new CardSearchResult("second")));
    for (var cardId : List.of("first", "second")) {
      fetchTcgService.seedCard(
          cardId,
          new GetCardResponse(
              cardId, "Squirtle", null, Map.of("tcgId", "502548", "scryfallId", "other")));
    }
    var unmappedRow = row("Squirtle", "normal", "502548");
    var unresolvableRow = row("Squirtle", "normal", "other");
    unresolvableRow.setSetCode("MEW");
    var ambiguousRow = row("Squirtle", "normal", "502548");
    ambiguousRow.setSetCode("MEW");
    var resolver = pokemonResolver(fetchTcgService);

    // act
    var unmapped = resolver.resolve(CTX, unmappedRow);
    var unresolvable = resolver.resolve(CTX, unresolvableRow);
    var ambiguous = resolver.resolve(CTX, ambiguousRow);

    // assert
    assertThat(unmapped)
        .isEqualTo(
            new AppraisalResult(AppraisalResult.Decision.REVIEW, "unmapped set", null, null));
    assertThat(unresolvable)
        .isEqualTo(
            new AppraisalResult(AppraisalResult.Decision.REVIEW, "unresolvable", null, null));
    assertThat(ambiguous)
        .isEqualTo(
            new AppraisalResult(AppraisalResult.Decision.REVIEW, "ambiguous match", null, null));
  }

  @Test
  void pokemonResolverShouldReturnReviewWhenSetCodeIsMissing() throws Exception {
    // arrange
    var row = row("Pikachu", "normal", "25");
    row.setSetCode(null);
    var resolver = pokemonResolver(new FakeFetchTcgService());

    // act
    var result = resolver.resolve(CTX, row);

    // assert
    assertThat(result)
        .isEqualTo(
            new AppraisalResult(AppraisalResult.Decision.REVIEW, "unmapped set", null, null));
  }

  @Test
  void pokemonJpResolverShouldUseTheFullCatalogNameAndSelectedFinish() throws Exception {
    // arrange
    record Printing(String name, String finish, String productId, String cardId) {}
    var printings =
        List.of(
            new Printing("Snorlax - 143/165", "normal", "566488", "snorlax-normal"),
            new Printing("Snorlax (181/165)", "holofoil", "577145", "snorlax-art-rare"),
            new Printing("Snorlax (Poké Ball Pattern)", "holofoil", "566685", "snorlax-pbp"),
            new Printing("Snorlax (Master Ball Pattern)", "holofoil", "566838", "snorlax-mbp"),
            new Printing(
                "Abomasnow - 008/184 (MIrror Holofoil)", "holofoil", "578358", "abomasnow-mh"),
            new Printing(
                "Budew (Mirror Foil) - 001/187",
                "holofoil",
                "602320",
                "pkjp_SV8a-001:187_C_MF_foil"),
            new Printing("Pikachu (GYM) - 291/SV-P", "normal", "shared", "pikachu-normal"),
            new Printing("Pikachu (GYM) - 291/SV-P", "holofoil", "shared", "pikachu-foil"));
    var fetchTcgService = new FakeFetchTcgService();
    fetchTcgService.seedSets("pkjp", "TEST", List.of(new CardSet(3413, "Set")));
    for (var printing : printings) {
      var finish = "normal".equals(printing.finish()) ? "normal" : "foil";
      fetchTcgService.seedSearchResult(
          "pkjp", 3413, printing.name(), finish, List.of(new CardSearchResult(printing.cardId())));
      fetchTcgService.seedCard(
          printing.cardId(),
          new GetCardResponse(
              printing.cardId(), printing.name(), null, Map.of("tcgId", printing.productId())));
    }
    var resolver =
        new PokemonJpAppraisalResolver(fetchTcgService, new FetchTcgCardMatcher(fetchTcgService));

    for (var printing : printings) {
      var row = row(printing.name(), printing.finish(), printing.productId());
      row.setSetCode("TEST");

      // act
      var result = resolver.resolve(CTX, row);

      // assert
      assertThat(result)
          .isEqualTo(
              new AppraisalResult(
                  AppraisalResult.Decision.RESOLVED, null, printing.cardId(), 3413));
      assertThat(row.getName()).isEqualTo(printing.name());
    }
  }

  @Test
  void pokemonJpResolverShouldFindLaterPageMatchAndDeduplicateAcrossMappedSets() throws Exception {
    // arrange
    var fetchTcgService = new FakeFetchTcgService();
    fetchTcgService.seedSets(
        "pkjp", "BW8", List.of(new CardSet(42, "First Set"), new CardSet(43, "Second Set")));
    var candidates = new ArrayList<CardSearchResult>();
    for (var i = 0; i < 100; i++) {
      candidates.add(new CardSearchResult("unrelated-" + i));
    }
    var match = new CardSearchResult("pkjp_opaque-008:184_R_foil");
    candidates.add(match);
    var name = "Pikachu (Poke Ball Pattern)";
    fetchTcgService.seedSearchResult("pkjp", 42, name, "foil", candidates);
    fetchTcgService.seedSearchResult("pkjp", 43, name, "foil", List.of(match));
    fetchTcgService.seedCard(
        match.id(), new GetCardResponse(match.id(), "Pikachu", null, Map.of("tcgId", "25")));
    var row = row(name, "holofoil", "25");
    row.setSetCode("BW8");
    var resolver =
        new PokemonJpAppraisalResolver(fetchTcgService, new FetchTcgCardMatcher(fetchTcgService));

    // act
    var result = resolver.resolve(CTX, row);

    // assert
    assertThat(result)
        .isEqualTo(new AppraisalResult(AppraisalResult.Decision.RESOLVED, null, match.id(), 42));
  }

  @Test
  void pokemonJpResolverShouldReturnReviewWhenAnExactPrintingCannotBeDetermined() throws Exception {
    // arrange
    var fetchTcgService = new FakeFetchTcgService();
    fetchTcgService.seedSets("pkjp", "SV2a", List.of(new CardSet(3413, "151")));
    fetchTcgService.seedSearchResult(
        "pkjp",
        3413,
        "Snorlax",
        "foil",
        List.of(
            new CardSearchResult("first"),
            new CardSearchResult("second"),
            new CardSearchResult("missing")));
    for (var id : List.of("first", "second")) {
      fetchTcgService.seedCard(
          id, new GetCardResponse(id, "Snorlax", null, Map.of("tcgId", "566838")));
    }
    var unmapped = row("Snorlax", "holofoil", "566838");
    var missing = row("Snorlax", "holofoil", "566685");
    missing.setSetCode("SV2a");
    var ambiguous = row("Snorlax", "holofoil", "566838");
    ambiguous.setSetCode("SV2a");
    var resolver =
        new PokemonJpAppraisalResolver(fetchTcgService, new FetchTcgCardMatcher(fetchTcgService));

    // act
    var unmappedResult = resolver.resolve(CTX, unmapped);
    var missingResult = resolver.resolve(CTX, missing);
    var ambiguousResult = resolver.resolve(CTX, ambiguous);

    // assert
    assertThat(unmappedResult)
        .isEqualTo(
            new AppraisalResult(AppraisalResult.Decision.REVIEW, "unmapped set", null, null));
    assertThat(missingResult)
        .isEqualTo(
            new AppraisalResult(AppraisalResult.Decision.REVIEW, "unresolvable", null, null));
    assertThat(ambiguousResult)
        .isEqualTo(
            new AppraisalResult(AppraisalResult.Decision.REVIEW, "ambiguous match", null, null));
  }

  private static PokemonAppraisalResolver pokemonResolver(FakeFetchTcgService fetchTcgService) {
    return new PokemonAppraisalResolver(fetchTcgService, new FetchTcgCardMatcher(fetchTcgService));
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

  private static ImportRowItem row(String name, String finish, String externalId) {
    var row = new ImportRowItem();
    row.setName(name);
    row.setSetCode("set");
    row.setFinish(finish);
    row.setCondition("NM");
    row.setExternalId(externalId);
    return row;
  }
}
