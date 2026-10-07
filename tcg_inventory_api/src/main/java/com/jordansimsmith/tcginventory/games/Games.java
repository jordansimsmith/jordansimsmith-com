package com.jordansimsmith.tcginventory.games;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

public class Games {
  public record Finish(String id, String displayName) {}

  public record ScanReviewImageRegion(
      String id, String displayName, double x, double y, double width, double height) {}

  public record Game(
      String id,
      String displayName,
      String externalSource,
      boolean scanningEnabled,
      boolean csvImportEnabled,
      List<Finish> finishes,
      List<ScanReviewImageRegion> scanReviewImageRegions) {
    public Game {
      finishes = List.copyOf(finishes);
      scanReviewImageRegions = List.copyOf(scanReviewImageRegions);
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
              new Finish("etched", "Etched")),
          List.of(
              new ScanReviewImageRegion("set_code", "Set code", 0, 0.9, 0.25, 0.1),
              new ScanReviewImageRegion("set_symbol", "Set symbol", 0.75, 0.535, 0.25, 0.1)));

  public static final Game POKEMON_ENGLISH =
      new Game(
          "pokemon",
          "Pokémon (EN)",
          "tcgplayer",
          false,
          false,
          List.of(
              new Finish("normal", "Normal"),
              new Finish("holofoil", "Holofoil"),
              new Finish("reverse_holofoil", "Reverse Holofoil")),
          List.of(
              new ScanReviewImageRegion("set_and_number", "Set and number", 0, 0.88, 0.5, 0.12),
              new ScanReviewImageRegion(
                  "artwork_stamps_left", "Left artwork stamps", 0, 0.32, 0.45, 0.22),
              new ScanReviewImageRegion(
                  "artwork_stamps_right", "Right artwork stamps", 0.55, 0.32, 0.45, 0.22)));

  private static final List<Game> GAMES = List.of(MAGIC_THE_GATHERING, POKEMON_ENGLISH);

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
