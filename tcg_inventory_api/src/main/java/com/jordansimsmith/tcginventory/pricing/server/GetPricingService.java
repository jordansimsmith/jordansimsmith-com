package com.jordansimsmith.tcginventory.pricing.server;

import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.service.ServiceException;
import com.jordansimsmith.tcginventory.pricing.client.GetPricingRequest;
import com.jordansimsmith.tcginventory.pricing.client.GetPricingResponse;

public class GetPricingService {
  private final PricingRepository pricingRepository;

  public GetPricingService(PricingRepository pricingRepository) {
    this.pricingRepository = pricingRepository;
  }

  public GetPricingResponse getPricing(RequestContext ctx, GetPricingRequest request)
      throws ServiceException {
    var item = pricingRepository.get(ctx.user());
    var priceFloor = PriceFloors.effective(item == null ? null : item.getPriceFloor());
    return new GetPricingResponse(priceFloor.toPlainString());
  }
}
