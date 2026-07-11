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
    var judged = searches.stream().filter(search -> search.judge() != null).toList();
    assertThat(judged)
        .extracting(SearchFactory.Search::searchTerm)
        .containsExactly("bulk", "collection", "assorted");
    assertThat(judged)
        .allSatisfy(
            search -> {
              assertThat(search.judge().prompt()).isEqualTo("prompts/mtg-bulk-judge.md");
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
              assertThat(search.baseUrl().getPath())
                  .isEqualTo("/a/marketplace/gaming/trading-cards/magic/search");
            });
  }
}
