package com.jordansimsmith.tcginventory.catalog.server;

import com.jordansimsmith.service.BadRequestException;
import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.service.ServiceException;
import com.jordansimsmith.tcginventory.catalog.client.SearchCardsRequest;
import com.jordansimsmith.tcginventory.catalog.client.SearchCardsResponse;
import com.jordansimsmith.tcginventory.games.client.GamesService;
import com.jordansimsmith.tcginventory.games.client.GetGameRequest;

public class SearchCardsService {
  private static final int DEFAULT_LIMIT = 20;
  private static final int MAX_LIMIT = 100;
  private static final int MIN_QUERY_LENGTH = 2;
  private static final int MAX_QUERY_LENGTH = 200;

  private final GamesService gamesService;
  private final MagicTheGatheringPrintingLookup magicTheGathering;
  private final PokemonPrintingLookup pokemon;

  public SearchCardsService(
      GamesService gamesService,
      MagicTheGatheringPrintingLookup magicTheGathering,
      PokemonPrintingLookup pokemon) {
    this.gamesService = gamesService;
    this.magicTheGathering = magicTheGathering;
    this.pokemon = pokemon;
  }

  public SearchCardsResponse searchCards(RequestContext ctx, SearchCardsRequest request)
      throws ServiceException {
    var limit = request.limit() == null ? DEFAULT_LIMIT : request.limit();
    if (limit < 1 || limit > MAX_LIMIT) {
      throw new BadRequestException("limit must be between 1 and 100");
    }
    if (request.game() == null || request.game().isBlank()) {
      throw new BadRequestException("game is required");
    }
    if (request.query() == null || request.query().isBlank()) {
      throw new BadRequestException("query is required");
    }
    if (request.finish() == null || request.finish().isBlank()) {
      throw new BadRequestException("finish is required");
    }

    var query = request.query().trim();
    if (query.length() < MIN_QUERY_LENGTH || query.length() > MAX_QUERY_LENGTH) {
      throw new BadRequestException("query must contain between 2 and 200 characters");
    }

    var game = gamesService.getGame(ctx, new GetGameRequest(request.game())).game();
    if (game.finishes().stream().noneMatch(finish -> finish.id().equals(request.finish()))) {
      throw new BadRequestException("finish is unsupported for game " + game.id());
    }
    return switch (game.id()) {
      case "mtg" ->
          magicTheGathering.searchCards(
              game, query, request.finish(), limit, request.continuation());
      case "pokemon" ->
          pokemon.searchCards(game, query, request.finish(), limit, request.continuation());
      default -> throw new BadRequestException("no catalog configured for game: " + game.id());
    };
  }
}
