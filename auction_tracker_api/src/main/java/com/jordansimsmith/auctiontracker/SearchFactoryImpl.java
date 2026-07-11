package com.jordansimsmith.auctiontracker;

import java.net.URI;
import java.util.List;

public class SearchFactoryImpl implements SearchFactory {
  private final List<Search> searches;

  public SearchFactoryImpl(URI baseUri) {
    this.searches =
        List.of(
            new Search(
                baseUri.resolve(
                    "/a/marketplace/computers/components/memory-ram/16gb-or-more/search"),
                "g.skill trident z 32gb ddr4",
                null,
                200.0,
                Condition.USED,
                null),
            new Search(
                baseUri.resolve("/a/marketplace/gaming/trading-cards/magic/search"),
                "bulk",
                null,
                100.0,
                Condition.USED,
                "prompts/mtg-bulk-judge.md"),
            new Search(
                baseUri.resolve("/a/marketplace/gaming/trading-cards/magic/search"),
                "collection",
                null,
                100.0,
                Condition.USED,
                "prompts/mtg-bulk-judge.md"),
            new Search(
                baseUri.resolve("/a/marketplace/gaming/trading-cards/magic/search"),
                "assorted",
                null,
                100.0,
                Condition.USED,
                "prompts/mtg-bulk-judge.md"));
  }

  @Override
  public List<Search> findSearches() {
    return searches;
  }
}
