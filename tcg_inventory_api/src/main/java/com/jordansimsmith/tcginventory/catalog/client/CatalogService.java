package com.jordansimsmith.tcginventory.catalog.client;

import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.service.ServiceException;

public interface CatalogService {
  GetCardResponse getCard(RequestContext ctx, GetCardRequest request) throws ServiceException;

  FindCardImagesResponse findCardImages(RequestContext ctx, FindCardImagesRequest request)
      throws ServiceException;

  FindCardsResponse findCards(RequestContext ctx, FindCardsRequest request) throws ServiceException;

  FindCardAlternativesResponse findCardAlternatives(
      RequestContext ctx, FindCardAlternativesRequest request) throws ServiceException;

  SearchCardsResponse searchCards(RequestContext ctx, SearchCardsRequest request)
      throws ServiceException;
}
