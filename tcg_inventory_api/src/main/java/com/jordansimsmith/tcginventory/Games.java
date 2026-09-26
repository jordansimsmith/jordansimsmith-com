package com.jordansimsmith.tcginventory;

import java.util.Map;
import java.util.Set;

public class Games {
  public record Game(
      String id,
      String externalSource,
      String fetchTcgExternalReferenceField,
      Set<String> finishes) {
    public Game {
      finishes = Set.copyOf(finishes);
    }
  }

  public static final Game MAGIC_THE_GATHERING =
      new Game("mtg", "scryfall", "scryfallId", Set.of("normal", "foil", "etched"));

  private static final Map<String, Game> GAMES =
      Map.of(MAGIC_THE_GATHERING.id(), MAGIC_THE_GATHERING);

  public static Game get(String id) {
    var game = GAMES.get(id);
    if (game == null) {
      throw new IllegalArgumentException("unsupported game: " + id);
    }
    return game;
  }
}
