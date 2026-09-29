package com.jordansimsmith.tcginventory.games;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

public class Games {
  public record Finish(String id, String displayName) {}

  public record Game(
      String id,
      String displayName,
      String externalSource,
      boolean scanningEnabled,
      boolean csvImportEnabled,
      List<Finish> finishes) {
    public Game {
      finishes = List.copyOf(finishes);
    }

    public boolean supportsFinish(String finishId) {
      return finishes.stream().anyMatch(finish -> finish.id().equals(finishId));
    }
  }

  public static final Game MAGIC_THE_GATHERING =
      new Game(
          "mtg",
          "Magic: The Gathering",
          "scryfall",
          true,
          true,
          List.of(
              new Finish("normal", "Normal"),
              new Finish("foil", "Foil"),
              new Finish("etched", "Etched")));

  private static final List<Game> GAMES = List.of(MAGIC_THE_GATHERING);

  private static final Map<String, Game> GAMES_BY_ID =
      GAMES.stream().collect(Collectors.toUnmodifiableMap(Game::id, Function.identity()));

  public static List<Game> all() {
    return GAMES;
  }

  public static Game get(String id) {
    var game = GAMES_BY_ID.get(id);
    if (game == null) {
      throw new IllegalArgumentException("unsupported game: " + id);
    }
    return game;
  }
}
