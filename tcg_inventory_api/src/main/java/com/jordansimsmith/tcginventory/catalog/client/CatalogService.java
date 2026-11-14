package com.jordansimsmith.tcginventory.catalog.client;

import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.service.ServiceException;

public interface CatalogService {
  GetCardResponse getCard(RequestContext ctx, GetCardRequest request) throws ServiceException;

  FindCardImagesResponse findCardImages(RequestContext ctx, FindCardImagesRequest request)
      throws ServiceException;

  FindCatalogCardsResponse findCards(RequestContext ctx, FindCatalogCardsRequest request)
      throws ServiceException;
}
