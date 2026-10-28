package com.jordansimsmith.tcginventory.games.server;

import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.service.ServiceException;
import com.jordansimsmith.tcginventory.games.client.FindGamesRequest;
import com.jordansimsmith.tcginventory.games.client.FindGamesResponse;
import com.jordansimsmith.tcginventory.games.client.GamesService;
import com.jordansimsmith.tcginventory.games.client.GetGameRequest;
import com.jordansimsmith.tcginventory.games.client.GetGameResponse;
import dagger.Module;
import dagger.Provides;
import javax.inject.Singleton;

@Module
public class GamesModule {
  @Provides
  @Singleton
  GamesService gamesService() {
    var getGameService = new GetGameService();
    var findGamesService = new FindGamesService();
    return new GamesService() {
      @Override
      public GetGameResponse getGame(RequestContext ctx, GetGameRequest request)
          throws ServiceException {
        return getGameService.getGame(ctx, request);
      }

      @Override
      public FindGamesResponse findGames(RequestContext ctx, FindGamesRequest request)
          throws ServiceException {
        return findGamesService.findGames(ctx, request);
      }
    };
  }
}
