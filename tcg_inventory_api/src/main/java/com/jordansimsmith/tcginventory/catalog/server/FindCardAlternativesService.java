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
  private final ScryfallPrintingLookup scryfall;
  private final TcgCsvPrintingLookup tcgCsv;

  public FindCardAlternativesService(
      GamesService gamesService, ScryfallPrintingLookup scryfall, TcgCsvPrintingLookup tcgCsv) {
    this.gamesService = gamesService;
    this.scryfall = scryfall;
    this.tcgCsv = tcgCsv;
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
          scryfall.findCardAlternatives(
              game, request.externalId(), request.finish(), limit, request.continuation());
      case "pokemon", "pokemon_jp" ->
          tcgCsv.findCardAlternatives(
              game,
              TcgCsvCatalogDefinition.forGame(game.id()),
              request.externalId(),
              request.finish(),
              limit,
              request.continuation());
      default -> throw new BadRequestException("no catalog configured for game: " + game.id());
    };
  }
}
