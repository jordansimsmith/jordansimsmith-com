package com.jordansimsmith.tcginventory.games.client;

import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.service.ServiceException;

public interface GamesService {
  GetGameResponse getGame(RequestContext ctx, GetGameRequest request) throws ServiceException;

  FindGamesResponse findGames(RequestContext ctx, FindGamesRequest request) throws ServiceException;
}
