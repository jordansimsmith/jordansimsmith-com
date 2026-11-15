package com.jordansimsmith.tcginventory.catalog.server;

import com.jordansimsmith.service.BadRequestException;
import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.service.ServiceException;
import com.jordansimsmith.tcginventory.catalog.client.FindCardAlternativesRequest;
import com.jordansimsmith.tcginventory.catalog.client.FindCardAlternativesResponse;
import com.jordansimsmith.tcginventory.games.client.GamesService;
import com.jordansimsmith.tcginventory.games.client.GetGameRequest;

public class FindCardAlternativesService {
  private static final int DEFAULT_LIMIT = 20;
  private static final int MAX_LIMIT = 100;

  private final GamesService gamesService;
  private final MagicTheGatheringPrintingLookup magicTheGathering;
  private final PokemonPrintingLookup pokemon;

  public FindCardAlternativesService(
      GamesService gamesService,
      MagicTheGatheringPrintingLookup magicTheGathering,
      PokemonPrintingLookup pokemon) {
    this.gamesService = gamesService;
    this.magicTheGathering = magicTheGathering;
    this.pokemon = pokemon;
  }

  public FindCardAlternativesResponse findCardAlternatives(
      RequestContext ctx, FindCardAlternativesRequest request) throws ServiceException {
    var limit = request.limit() == null ? DEFAULT_LIMIT : request.limit();
    if (limit < 1 || limit > MAX_LIMIT) {
      throw new BadRequestException("limit must be between 1 and 100");
    }
    if (request.game() == null || request.game().isBlank()) {
      throw new BadRequestException("game is required");
    }
    if (request.externalId() == null || request.externalId().isBlank()) {
      throw new BadRequestException("external_id is required");
    }
    if (request.finish() == null || request.finish().isBlank()) {
      throw new BadRequestException("finish is required");
    }

    var game = gamesService.getGame(ctx, new GetGameRequest(request.game())).game();
    if (game.finishes().stream().noneMatch(finish -> finish.id().equals(request.finish()))) {
      throw new BadRequestException("finish is unsupported for game " + game.id());
    }
    return switch (game.id()) {
      case "mtg" ->
          magicTheGathering.findCardAlternatives(
              game, request.externalId(), request.finish(), limit, request.continuation());
      case "pokemon" ->
          pokemon.findCardAlternatives(
              game, request.externalId(), request.finish(), limit, request.continuation());
      default -> throw new BadRequestException("no catalog configured for game: " + game.id());
    };
  }
}
