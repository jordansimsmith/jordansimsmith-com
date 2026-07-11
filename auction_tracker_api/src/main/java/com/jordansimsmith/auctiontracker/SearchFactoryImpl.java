package com.jordansimsmith.auctiontracker;

import java.net.URI;
import java.util.List;

public class SearchFactoryImpl implements SearchFactory {
  private record SearchDefinition(
      String path,
      String searchTerm,
      Double minPrice,
      Double maxPrice,
      Condition condition,
      String judgePrompt) {}

  private static final List<SearchDefinition> SEARCH_DEFINITIONS =
      List.of(
          new SearchDefinition(
              "/a/marketplace/computers/components/memory-ram/16gb-or-more/search",
              "g.skill trident z 32gb ddr4",
              null,
              200.0,
              Condition.USED,
              null),
          new SearchDefinition(
              "/a/marketplace/gaming/trading-cards/magic/search",
              "bulk",
              null,
              100.0,
              Condition.USED,
              "prompts/mtg-bulk-judge.md"),
          new SearchDefinition(
              "/a/marketplace/gaming/trading-cards/magic/search",
              "collection",
              null,
              100.0,
              Condition.USED,
              "prompts/mtg-bulk-judge.md"),
          new SearchDefinition(
              "/a/marketplace/gaming/trading-cards/magic/search",
              "assorted",
              null,
              100.0,
              Condition.USED,
              "prompts/mtg-bulk-judge.md"));

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
                    definition.condition(),
                    definition.judgePrompt()))
        .toList();
  }
}
