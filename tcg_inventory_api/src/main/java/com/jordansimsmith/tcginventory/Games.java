package com.jordansimsmith.tcginventory;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

public class Games {
  public record Game(String id, String externalSource, Set<String> finishes) {
    public Game {
      finishes = Set.copyOf(finishes);
    }
  }

  public static final Game MAGIC_THE_GATHERING =
      new Game("mtg", "scryfall", Set.of("normal", "foil", "etched"));

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
