package com.jordansimsmith.tcginventory.games.client;

import com.jordansimsmith.service.BadRequestException;
import com.jordansimsmith.service.NotFoundException;
import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.service.ServiceException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class FakeGamesService implements GamesService {
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

  private final Map<String, Game> gamesById = new LinkedHashMap<>();

  public FakeGamesService() {
    seed(MAGIC_THE_GATHERING);
    seed(POKEMON_ENGLISH);
  }

  public void seed(Game game) {
    gamesById.put(game.id(), game);
  }

  @Override
  public GetGameResponse getGame(RequestContext ctx, GetGameRequest request)
      throws ServiceException {
    if (request.gameId() == null || request.gameId().isBlank()) {
      throw new BadRequestException("game_id is required");
    }
    var game = gamesById.get(request.gameId());
    if (game == null) {
      throw new NotFoundException("unsupported game: " + request.gameId());
    }
    return new GetGameResponse(game);
  }

  @Override
  public FindGamesResponse findGames(RequestContext ctx, FindGamesRequest request) {
    return new FindGamesResponse(new ArrayList<>(gamesById.values()));
  }
}
