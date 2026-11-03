package com.jordansimsmith.tcginventory.listings.client;

import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.service.ServiceException;

public interface ListingService {
  FindListingsResponse findListings(RequestContext ctx, FindListingsRequest request)
      throws ServiceException;
}
