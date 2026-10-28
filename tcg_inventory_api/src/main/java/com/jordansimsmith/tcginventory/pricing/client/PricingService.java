package com.jordansimsmith.tcginventory.pricing.client;

import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.service.ServiceException;

public interface PricingService {
  GetPricingResponse getPricing(RequestContext ctx, GetPricingRequest request)
      throws ServiceException;

  UpdatePricingResponse updatePricing(RequestContext ctx, UpdatePricingRequest request)
      throws ServiceException;

  GetPriceResponse getPrice(RequestContext ctx, GetPriceRequest request) throws ServiceException;
}
