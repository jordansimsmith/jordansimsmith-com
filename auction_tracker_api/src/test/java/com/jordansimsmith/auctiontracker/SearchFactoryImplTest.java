package com.jordansimsmith.auctiontracker;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import org.junit.jupiter.api.Test;

public class SearchFactoryImplTest {
  @Test
  void findSearchesShouldResolveSearchPathsAgainstConfiguredBaseUrl() {
    // arrange
    var factory = new SearchFactoryImpl(URI.create("http://trademe-stub:8080"));

    // act
    var searches = factory.findSearches();

    // assert
    assertThat(searches).isNotEmpty();
    assertThat(searches.getFirst().baseUrl().toString())
        .isEqualTo(
            "http://trademe-stub:8080/a/marketplace/computers/components/memory-ram/16gb-or-more/search");
    assertThat(searches)
        .allSatisfy(search -> assertThat(search.baseUrl().getHost()).isEqualTo("trademe-stub"));
  }

  @Test
  void findSearchesShouldAttachSharedJudgeConfigToMtgSearches() {
    // arrange
    var factory = new SearchFactoryImpl(URI.create("https://www.trademe.co.nz"));

    // act
    var searches = factory.findSearches();

    // assert
    var judged =
        searches.stream()
            .filter(
                search ->
                    search.judge() != null
                        && search.judge().prompt().equals("prompts/mtg-bulk-judge.md"))
            .toList();
    assertThat(judged)
        .extracting(SearchFactory.Search::searchTerm)
        .containsExactly("bulk", "collection", "assorted");
    assertThat(judged)
        .allSatisfy(
            search -> {
              assertThat(search.judge().model()).isEqualTo("gpt-5.4-mini");
              assertThat(search.judge().reasoningEffort()).isEqualTo("none");
              assertThat(search.judge().criteria())
                  .containsExactly(
                      "mtg_cards",
                      "bulk_scale",
                      "not_basic_lands",
                      "not_universes_beyond",
                      "civilian_seller",
                      "fixed_collection");
              assertThat(search.maxPrice()).isEqualTo(200.0);
              assertThat(search.condition()).isEqualTo(SearchFactory.Condition.USED);
              assertThat(search.baseUrl().getPath())
                  .isEqualTo("/a/marketplace/gaming/trading-cards/magic/search");
            });
  }

  @Test
  void findSearchesShouldAttachSharedJudgeConfigToRamSearches() {
    // arrange
    var factory = new SearchFactoryImpl(URI.create("https://www.trademe.co.nz"));

    // act
    var searches = factory.findSearches();

    // assert
    var judged =
        searches.stream()
            .filter(
                search ->
                    search.judge() != null
                        && search.judge().prompt().equals("prompts/ram-judge.md"))
            .toList();
    assertThat(judged)
        .extracting(SearchFactory.Search::searchTerm)
        .containsExactly("g.skill", "gskill", "trident z");
    assertThat(judged)
        .allSatisfy(
            search -> {
              assertThat(search.judge().model()).isEqualTo("gpt-5.4-nano");
              assertThat(search.judge().reasoningEffort()).isEqualTo("low");
              assertThat(search.judge().criteria())
                  .containsExactly(
                      "trident_z_family",
                      "ddr4",
                      "kit_2x16gb",
                      "speed_3200",
                      "timings_cl16",
                      "desktop_udimm");
              assertThat(search.minPrice()).isNull();
              assertThat(search.maxPrice()).isEqualTo(200.0);
              assertThat(search.condition()).isEqualTo(SearchFactory.Condition.USED);
              assertThat(search.baseUrl().getPath())
                  .isEqualTo("/a/marketplace/computers/components/memory-ram/16gb-or-more/search");
            });
  }

  @Test
  void findSearchesShouldJudgeEverySearch() {
    // arrange
    var factory = new SearchFactoryImpl(URI.create("https://www.trademe.co.nz"));

    // act
    var searches = factory.findSearches();

    // assert
    assertThat(searches).hasSize(6);
    assertThat(searches).allSatisfy(search -> assertThat(search.judge()).isNotNull());
  }
}
