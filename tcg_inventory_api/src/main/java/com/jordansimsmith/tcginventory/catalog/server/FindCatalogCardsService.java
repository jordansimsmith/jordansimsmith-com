package com.jordansimsmith.tcginventory.catalog.server;

import com.jordansimsmith.service.BadRequestException;
import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.service.ServiceException;
import com.jordansimsmith.tcginventory.catalog.client.FindCatalogCardsRequest;
import com.jordansimsmith.tcginventory.catalog.client.FindCatalogCardsResponse;
import com.jordansimsmith.tcginventory.games.client.GamesService;
import com.jordansimsmith.tcginventory.games.client.GetGameRequest;
import java.util.LinkedHashSet;
import java.util.List;

public class FindCatalogCardsService {
  private static final int MAX_EXTERNAL_IDS = 100;

  private final GamesService gamesService;
  private final MagicTheGatheringPrintingLookup magicTheGathering;
  private final PokemonPrintingLookup pokemon;

  public FindCatalogCardsService(
      GamesService gamesService,
      MagicTheGatheringPrintingLookup magicTheGathering,
      PokemonPrintingLookup pokemon) {
    this.gamesService = gamesService;
    this.magicTheGathering = magicTheGathering;
    this.pokemon = pokemon;
  }

  public FindCatalogCardsResponse findCards(RequestContext ctx, FindCatalogCardsRequest request)
      throws ServiceException {
    if (request.externalIds() == null || request.externalIds().size() > MAX_EXTERNAL_IDS) {
      throw new BadRequestException("external_ids must contain between 0 and 100 values");
    }
    if (request.externalIds().stream().anyMatch(id -> id == null || id.isBlank())) {
      throw new BadRequestException("external_ids must not contain blank values");
    }

    var game = gamesService.getGame(ctx, new GetGameRequest(request.game())).game();
    if (request.externalIds().isEmpty()) {
      return new FindCatalogCardsResponse(List.of());
    }

    var ids = new LinkedHashSet<>(request.externalIds());
    var cards =
        switch (game.id()) {
          case "mtg" -> magicTheGathering.findCards(game, List.copyOf(ids));
          case "pokemon" -> pokemon.findCards(game, ids);
          default -> throw new BadRequestException("no catalog configured for game: " + game.id());
        };
    return new FindCatalogCardsResponse(cards);
  }
}
