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
        .isEqualTo("http://trademe-stub:8080/a/marketplace/computers/components/cpus/amd/search");
    assertThat(searches)
        .allSatisfy(search -> assertThat(search.baseUrl().getHost()).isEqualTo("trademe-stub"));
  }

  @Test
  void findSearchesShouldAttachJudgePromptToMtgSearches() {
    // arrange
    var factory = new SearchFactoryImpl(URI.create("https://www.trademe.co.nz"));

    // act
    var searches = factory.findSearches();

    // assert
    var judged = searches.stream().filter(search -> search.judgePrompt() != null).toList();
    assertThat(judged)
        .extracting(SearchFactory.Search::searchTerm)
        .containsExactly("bulk", "collection", "assorted");
    assertThat(judged)
        .allSatisfy(
            search -> {
              assertThat(search.judgePrompt()).isEqualTo("prompts/mtg-bulk-judge.md");
              assertThat(search.baseUrl().getPath())
                  .isEqualTo("/a/marketplace/gaming/trading-cards/magic/search");
            });
  }
}
