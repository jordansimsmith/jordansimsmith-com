package com.jordansimsmith.tcginventory.games.server;

import com.jordansimsmith.service.BadRequestException;
import com.jordansimsmith.service.NotFoundException;
import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.service.ServiceException;
import com.jordansimsmith.tcginventory.games.client.GetGameRequest;
import com.jordansimsmith.tcginventory.games.client.GetGameResponse;

public class GetGameService {
  public GetGameResponse getGame(RequestContext ctx, GetGameRequest request)
      throws ServiceException {
    if (request.gameId() == null || request.gameId().isBlank()) {
      throw new BadRequestException("game_id is required");
    }
    try {
      return new GetGameResponse(RegisteredGames.get(request.gameId()));
    } catch (IllegalArgumentException e) {
      throw new NotFoundException(e.getMessage());
    }
  }
}
