package com.jordansimsmith.tcginventory.games.server;

import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.service.ServiceException;
import com.jordansimsmith.tcginventory.games.client.FindGamesRequest;
import com.jordansimsmith.tcginventory.games.client.FindGamesResponse;

public class FindGamesService {
  public FindGamesResponse findGames(RequestContext ctx, FindGamesRequest request)
      throws ServiceException {
    return new FindGamesResponse(RegisteredGames.all());
  }
}
