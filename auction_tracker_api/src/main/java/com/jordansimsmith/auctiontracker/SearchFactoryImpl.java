package com.jordansimsmith.auctiontracker;

import java.net.URI;
import java.util.List;

public class SearchFactoryImpl implements SearchFactory {
  private static final Judge MTG_JUDGE =
      new Judge(
          "prompts/mtg-bulk-judge.md",
          "gpt-5.4-mini",
          "none",
          List.of("mtg_cards", "bulk_scale", "not_basic_lands", "fixed_collection"));

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

  private static final Judge POKEMON_JUDGE =
      new Judge(
          "prompts/pokemon-bulk-judge.md",
          "gpt-6-luna",
          "none",
          List.of(
              "pokemon_cards",
              "bulk_scale",
              "accepted_language",
              "not_basic_energy",
              "not_mega_evolution_era",
              "acceptable_condition",
              "fixed_collection"));

  private static final String RAM_SEARCH_PATH =
      "/a/marketplace/computers/components/memory-ram/16gb-or-more/search";
  private static final String POKEMON_SEARCH_PATH =
      "/a/marketplace/gaming/trading-cards/pokemon/search";

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
                MTG_JUDGE),
            new Search(
                "pokemon-bulk",
                baseUri.resolve(POKEMON_SEARCH_PATH),
                "bulk",
                null,
                200.0,
                Condition.USED,
                POKEMON_JUDGE),
            new Search(
                "pokemon-collection",
                baseUri.resolve(POKEMON_SEARCH_PATH),
                "collection",
                null,
                200.0,
                Condition.USED,
                POKEMON_JUDGE),
            new Search(
                "pokemon-assorted",
                baseUri.resolve(POKEMON_SEARCH_PATH),
                "assorted",
                null,
                200.0,
                Condition.USED,
                POKEMON_JUDGE),
            new Search(
                "pokemon-clear-out",
                baseUri.resolve(POKEMON_SEARCH_PATH),
                "clear out",
                null,
                200.0,
                Condition.USED,
                POKEMON_JUDGE),
            new Search(
                "pokemon-clearout",
                baseUri.resolve(POKEMON_SEARCH_PATH),
                "clearout",
                null,
                200.0,
                Condition.USED,
                POKEMON_JUDGE),
            new Search(
                "pokemon-lot",
                baseUri.resolve(POKEMON_SEARCH_PATH),
                "lot",
                null,
                200.0,
                Condition.USED,
                POKEMON_JUDGE),
            new Search(
                "pokemon-one-dollar-reserve",
                baseUri.resolve(POKEMON_SEARCH_PATH),
                "$1 reserve",
                null,
                200.0,
                Condition.USED,
                POKEMON_JUDGE));
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
