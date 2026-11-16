package com.jordansimsmith.tcginventory.catalog.server;

import com.jordansimsmith.service.BadRequestException;
import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.service.ServiceException;
import com.jordansimsmith.tcginventory.catalog.client.GetCardRequest;
import com.jordansimsmith.tcginventory.catalog.client.GetCardResponse;
import com.jordansimsmith.tcginventory.games.client.GamesService;
import com.jordansimsmith.tcginventory.games.client.GetGameRequest;

public class GetCardService {
  private final GamesService gamesService;
  private final ScryfallPrintingLookup scryfall;
  private final TcgCsvPrintingLookup tcgCsv;

  public GetCardService(
      GamesService gamesService, ScryfallPrintingLookup scryfall, TcgCsvPrintingLookup tcgCsv) {
    this.gamesService = gamesService;
    this.scryfall = scryfall;
    this.tcgCsv = tcgCsv;
  }

  public GetCardResponse getCard(RequestContext ctx, GetCardRequest request)
      throws ServiceException {
    if (request.game() == null || request.game().isBlank()) {
      throw new BadRequestException("game is required");
    }
    if (request.externalId() == null || request.externalId().isBlank()) {
      throw new BadRequestException("external_id is required");
    }

    var game = gamesService.getGame(ctx, new GetGameRequest(request.game())).game();
    var card =
        switch (game.id()) {
          case "mtg" -> scryfall.getCard(game, request.externalId());
          case "pokemon", "pokemon_jp" ->
              tcgCsv.getCard(
                  game, TcgCsvCatalogDefinition.forGame(game.id()), request.externalId());
          default -> throw new BadRequestException("no catalog configured for game: " + game.id());
        };
    return new GetCardResponse(card);
  }
}
