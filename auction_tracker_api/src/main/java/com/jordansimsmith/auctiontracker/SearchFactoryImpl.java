package com.jordansimsmith.auctiontracker;

import java.net.URI;
import java.util.List;

public class SearchFactoryImpl implements SearchFactory {
  private record SearchDefinition(
      String path, String searchTerm, Double minPrice, Double maxPrice, Condition condition) {}

  private static final List<SearchDefinition> SEARCH_DEFINITIONS =
      List.of(
          new SearchDefinition(
              "/a/marketplace/sports/golf/irons/steel-shaft/search",
              "titleist iron",
              null,
              75.0,
              Condition.USED),
          new SearchDefinition(
              "/a/marketplace/sports/golf/irons/steel-shaft/search",
              "ping iron",
              null,
              75.0,
              Condition.USED),
          new SearchDefinition(
              "/a/marketplace/sports/golf/irons/steel-shaft/search",
              "taylormade iron",
              null,
              75.0,
              Condition.USED),
          new SearchDefinition(
              "/a/marketplace/sports/golf/irons/steel-shaft/search",
              "callaway iron",
              null,
              75.0,
              Condition.USED),
          new SearchDefinition(
              "/a/marketplace/computers/components/cpus/amd/search",
              "ryzen 7 5700x3d",
              null,
              500.0,
              Condition.USED),
          new SearchDefinition(
              "/a/marketplace/computers/components/cpus/amd/search",
              "ryzen 7 5800x3d",
              null,
              500.0,
              Condition.USED),
          new SearchDefinition(
              "/a/marketplace/computers/components/memory-ram/16gb-or-more/search",
              "g.skill trident z 32gb ddr4",
              null,
              200.0,
              Condition.USED));

  private final URI baseUri;

  public SearchFactoryImpl(URI baseUri) {
    this.baseUri = baseUri;
  }

  @Override
  public List<Search> findSearches() {
    return SEARCH_DEFINITIONS.stream()
        .map(
            definition ->
                new Search(
                    baseUri.resolve(definition.path()),
                    definition.searchTerm(),
                    definition.minPrice(),
                    definition.maxPrice(),
                    definition.condition()))
        .toList();
  }
}
