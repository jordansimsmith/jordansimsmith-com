package com.jordansimsmith.tcginventory.fetchtcg.client;

import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.service.ServiceException;

public interface FetchTcgService {
  GetAccountResponse getAccount(RequestContext ctx, GetAccountRequest request)
      throws ServiceException;

  UpdateCredentialsResponse updateCredentials(RequestContext ctx, UpdateCredentialsRequest request)
      throws ServiceException;

  GetCardResponse getCard(RequestContext ctx, GetCardRequest request) throws ServiceException;

  FindCardListingsResponse findCardListings(RequestContext ctx, FindCardListingsRequest request)
      throws ServiceException;

  FindOffersResponse findOffers(RequestContext ctx, FindOffersRequest request)
      throws ServiceException;

  UpdateListingResponse updateListing(RequestContext ctx, UpdateListingRequest request)
      throws ServiceException;

  DeleteListingResponse deleteListing(RequestContext ctx, DeleteListingRequest request)
      throws ServiceException;

  CreateListingImageResponse createListingImage(
      RequestContext ctx, CreateListingImageRequest request) throws ServiceException;
}
