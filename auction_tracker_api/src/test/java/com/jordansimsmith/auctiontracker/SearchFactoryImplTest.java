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
        .isEqualTo("http://trademe-stub:8080/a/marketplace/sports/golf/irons/steel-shaft/search");
    assertThat(searches)
        .allSatisfy(search -> assertThat(search.baseUrl().getHost()).isEqualTo("trademe-stub"));
  }
}
