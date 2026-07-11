package com.jordansimsmith.auctiontracker;

import java.net.URI;
import java.util.List;

public class SearchFactoryImpl implements SearchFactory {
  private static final Judge MTG_JUDGE =
      new Judge(
          "prompts/mtg-bulk-judge.md",
          "gpt-5.4-mini",
          "none",
          List.of(
              "mtg_cards",
              "bulk_scale",
              "not_basic_lands",
              "not_universes_beyond",
              "civilian_seller",
              "fixed_collection"));

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
                MTG_JUDGE),
            new Search(
                baseUri.resolve("/a/marketplace/gaming/trading-cards/magic/search"),
                "collection",
                null,
                100.0,
                Condition.USED,
                MTG_JUDGE),
            new Search(
                baseUri.resolve("/a/marketplace/gaming/trading-cards/magic/search"),
                "assorted",
                null,
                100.0,
                Condition.USED,
                MTG_JUDGE));
  }

  @Override
  public List<Search> findSearches() {
    return searches;
  }
}
