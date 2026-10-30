package com.jordansimsmith.tcginventory.catalog.server;

import com.jordansimsmith.service.BadRequestException;
import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.service.ServiceException;
import com.jordansimsmith.tcginventory.catalog.client.CardImages;
import com.jordansimsmith.tcginventory.catalog.client.FindCardImagesRequest;
import com.jordansimsmith.tcginventory.catalog.client.FindCardImagesResponse;
import com.jordansimsmith.tcginventory.games.client.GamesService;
import com.jordansimsmith.tcginventory.games.client.GetGameRequest;
import java.util.LinkedHashSet;
import java.util.List;

public class FindCardImagesService {
  private static final int MAX_EXTERNAL_IDS = 100;

  private final GamesService gamesService;

  public FindCardImagesService(GamesService gamesService) {
    this.gamesService = gamesService;
  }

  public FindCardImagesResponse findCardImages(RequestContext ctx, FindCardImagesRequest request)
      throws ServiceException {
    if (request.game() == null || request.game().isBlank()) {
      throw new BadRequestException("game is required");
    }
    if (request.externalIds() == null
        || request.externalIds().isEmpty()
        || request.externalIds().size() > MAX_EXTERNAL_IDS) {
      throw new BadRequestException("external_ids must contain between 1 and 100 values");
    }
    if (request.externalIds().stream().anyMatch(id -> id == null || id.isBlank())) {
      throw new BadRequestException("external_ids must not contain blank values");
    }

    var game = gamesService.getGame(ctx, new GetGameRequest(request.game())).game();
    List<CardImages> images =
        new LinkedHashSet<>(request.externalIds())
            .stream()
                .map(
                    externalId ->
                        new CardImages(
                            externalId, CardImageUrls.forSource(game.externalSource(), externalId)))
                .toList();
    return new FindCardImagesResponse(images);
  }
}
