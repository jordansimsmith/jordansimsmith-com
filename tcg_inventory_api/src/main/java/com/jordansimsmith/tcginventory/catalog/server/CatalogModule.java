package com.jordansimsmith.tcginventory.catalog.server;

import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.service.ServiceException;
import com.jordansimsmith.tcginventory.catalog.client.CatalogService;
import com.jordansimsmith.tcginventory.catalog.client.FindCardImagesRequest;
import com.jordansimsmith.tcginventory.catalog.client.FindCardImagesResponse;
import com.jordansimsmith.tcginventory.games.client.GamesService;
import dagger.Module;
import dagger.Provides;
import javax.inject.Singleton;

@Module
public class CatalogModule {
  @Provides
  @Singleton
  CatalogService catalogService(GamesService gamesService) {
    var findCardImagesService = new FindCardImagesService(gamesService);
    return new CatalogService() {
      @Override
      public FindCardImagesResponse findCardImages(
          RequestContext ctx, FindCardImagesRequest request) throws ServiceException {
        return findCardImagesService.findCardImages(ctx, request);
      }
    };
  }
}
