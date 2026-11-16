package com.jordansimsmith.tcginventory.catalog.server;

import java.util.List;

public record TcgCsvCatalogDefinition(String gameId, int categoryId, List<FinishMapping> finishes) {
  public static TcgCsvCatalogDefinition forGame(String gameId) {
    return switch (gameId) {
      case "pokemon" ->
          new TcgCsvCatalogDefinition(
              "pokemon",
              3,
              List.of(
                  new FinishMapping("Normal", "normal"),
                  new FinishMapping("Holofoil", "holofoil"),
                  new FinishMapping("Reverse Holofoil", "reverse_holofoil")));
      case "pokemon_jp" ->
          new TcgCsvCatalogDefinition(
              "pokemon_jp",
              85,
              List.of(
                  new FinishMapping("Normal", "normal"),
                  new FinishMapping("Holofoil", "holofoil")));
      case null, default ->
          throw new IllegalArgumentException("unsupported catalog game: " + gameId);
    };
  }

  public TcgCsvCatalogDefinition {
    finishes = List.copyOf(finishes);
  }

  public record FinishMapping(String sourceSubtype, String finishId) {}
}
