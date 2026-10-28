package com.jordansimsmith.tcginventory.pricing.client;

import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.service.ServiceException;

public interface PricingService {
  GetPriceResponse getPrice(RequestContext ctx, GetPriceRequest request) throws ServiceException;
}
