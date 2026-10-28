package com.jordansimsmith.tcginventory.pricing.server;

import com.jordansimsmith.service.BadRequestException;
import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.service.ServiceException;
import com.jordansimsmith.tcginventory.pricing.client.UpdatePricingRequest;
import com.jordansimsmith.tcginventory.pricing.client.UpdatePricingResponse;

public class UpdatePricingService {
  private static final String INVALID_PRICE_FLOOR_MESSAGE =
      "price_floor must be a nonnegative decimal with at most two places";

  private final PricingRepository pricingRepository;

  public UpdatePricingService(PricingRepository pricingRepository) {
    this.pricingRepository = pricingRepository;
  }

  public UpdatePricingResponse updatePricing(RequestContext ctx, UpdatePricingRequest request)
      throws ServiceException {
    var requestedPriceFloor = request.priceFloor();
    if (requestedPriceFloor == null) {
      var item = pricingRepository.get(ctx.user());
      var priceFloor = PriceFloors.effective(item == null ? null : item.getPriceFloor());
      return new UpdatePricingResponse(priceFloor.toPlainString());
    }
    if (requestedPriceFloor.signum() < 0 || requestedPriceFloor.scale() > 2) {
      throw new BadRequestException(INVALID_PRICE_FLOOR_MESSAGE);
    }

    var effectivePriceFloor = requestedPriceFloor.setScale(2);
    // TODO: reject floor updates while the user's background job is active through the Jobs client.
    pricingRepository.update(PricingItem.create(ctx.user(), effectivePriceFloor.toPlainString()));
    return new UpdatePricingResponse(effectivePriceFloor.toPlainString());
  }
}
