package com.jordansimsmith.tcginventory.games.server;

import com.jordansimsmith.tcginventory.games.client.Finish;
import com.jordansimsmith.tcginventory.games.client.Game;
import com.jordansimsmith.tcginventory.games.client.Language;
import com.jordansimsmith.tcginventory.games.client.ScanReviewImageRegion;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

public class RegisteredGames {
  public static final Game MAGIC_THE_GATHERING =
      new Game(
          "mtg",
          "Magic: The Gathering",
          "scryfall",
          Language.ENGLISH,
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
          Language.ENGLISH,
          true,
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

  public static final Game POKEMON_JAPANESE =
      new Game(
          "pokemon_jp",
          "Pokémon (JP)",
          "tcgplayer",
          Language.JAPANESE,
          true,
          false,
          List.of(new Finish("normal", "Normal"), new Finish("holofoil", "Holofoil")),
          List.of(
              new ScanReviewImageRegion("set_and_number", "Set and number", 0, 0.85, 0.5, 0.15),
              new ScanReviewImageRegion(
                  "artwork_stamps_left", "Left artwork stamps", 0, 0.3, 0.48, 0.28),
              new ScanReviewImageRegion(
                  "artwork_stamps_right", "Right artwork stamps", 0.52, 0.3, 0.48, 0.28),
              new ScanReviewImageRegion(
                  "lower_right_stamp_and_foil",
                  "Lower-right stamp and foil",
                  0.42,
                  0.54,
                  0.58,
                  0.35)));

  private static final List<Game> GAMES =
      List.of(MAGIC_THE_GATHERING, POKEMON_ENGLISH, POKEMON_JAPANESE);

  private static final Map<String, Game> GAMES_BY_ID =
      GAMES.stream().collect(Collectors.toUnmodifiableMap(Game::id, Function.identity()));

  public static List<Game> all() {
    return GAMES;
  }

  public static Game get(String gameId) {
    var game = GAMES_BY_ID.get(gameId);
    if (game == null) {
      throw new IllegalArgumentException("unsupported game: " + gameId);
    }
    return game;
  }
}
