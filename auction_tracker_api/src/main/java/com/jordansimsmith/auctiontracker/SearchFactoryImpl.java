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
              "mtg_cards", "bulk_scale", "not_basic_lands", "civilian_seller", "fixed_collection"));

  private static final Judge RAM_JUDGE =
      new Judge(
          "prompts/ram-judge.md",
          "gpt-5.4-nano",
          "low",
          List.of(
              "trident_z_family",
              "ddr4",
              "kit_2x16gb",
              "speed_3200",
              "timings_cl16",
              "desktop_udimm"));

  private static final String RAM_SEARCH_PATH =
      "/a/marketplace/computers/components/memory-ram/16gb-or-more/search";

  private final List<Search> searches;

  public SearchFactoryImpl(URI baseUri) {
    this.searches =
        List.of(
            new Search(
                "ram-g-skill",
                baseUri.resolve(RAM_SEARCH_PATH),
                "g.skill",
                null,
                200.0,
                Condition.USED,
                RAM_JUDGE),
            new Search(
                "ram-gskill",
                baseUri.resolve(RAM_SEARCH_PATH),
                "gskill",
                null,
                200.0,
                Condition.USED,
                RAM_JUDGE),
            new Search(
                "ram-trident-z",
                baseUri.resolve(RAM_SEARCH_PATH),
                "trident z",
                null,
                200.0,
                Condition.USED,
                RAM_JUDGE),
            new Search(
                "mtg-bulk",
                baseUri.resolve("/a/marketplace/gaming/trading-cards/magic/search"),
                "bulk",
                null,
                200.0,
                Condition.USED,
                MTG_JUDGE),
            new Search(
                "mtg-collection",
                baseUri.resolve("/a/marketplace/gaming/trading-cards/magic/search"),
                "collection",
                null,
                200.0,
                Condition.USED,
                MTG_JUDGE),
            new Search(
                "mtg-assorted",
                baseUri.resolve("/a/marketplace/gaming/trading-cards/magic/search"),
                "assorted",
                null,
                200.0,
                Condition.USED,
                MTG_JUDGE),
            new Search(
                "mtg-clear-out",
                baseUri.resolve("/a/marketplace/gaming/trading-cards/magic/search"),
                "clear out",
                null,
                200.0,
                Condition.USED,
                MTG_JUDGE),
            new Search(
                "mtg-clearout",
                baseUri.resolve("/a/marketplace/gaming/trading-cards/magic/search"),
                "clearout",
                null,
                200.0,
                Condition.USED,
                MTG_JUDGE),
            new Search(
                "mtg-lot",
                baseUri.resolve("/a/marketplace/gaming/trading-cards/magic/search"),
                "lot",
                null,
                200.0,
                Condition.USED,
                MTG_JUDGE),
            new Search(
                "mtg-one-dollar-reserve",
                baseUri.resolve("/a/marketplace/gaming/trading-cards/magic/search"),
                "$1 reserve",
                null,
                200.0,
                Condition.USED,
                MTG_JUDGE));
  }

  @Override
  public List<Search> findSearches() {
    return searches;
  }

  @Override
  public Search getSearch(String id) {
    return searches.stream()
        .filter(search -> search.id().equals(id))
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("unknown search: " + id));
  }
}
