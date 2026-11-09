package com.jordansimsmith.tcginventory.pricing.client;

import com.jordansimsmith.service.NotFoundException;
import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.service.ServiceException;
import com.jordansimsmith.tcginventory.inventory.client.Condition;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

public class FakePricingService implements PricingService {
  private final Map<Key, GetPriceResponse> prices = new HashMap<>();
  private final Map<String, String> priceFloors = new HashMap<>();
  private final Map<String, GetRepricingResponse> repricings = new HashMap<>();

  @Override
  public GetPricingResponse getPricing(RequestContext ctx, GetPricingRequest request) {
    return new GetPricingResponse(priceFloors.getOrDefault(ctx.user(), "0.25"));
  }

  @Override
  public UpdatePricingResponse updatePricing(RequestContext ctx, UpdatePricingRequest request) {
    var priceFloor = request.priceFloor();
    if (priceFloor != null) {
      priceFloors.put(ctx.user(), priceFloor.setScale(2).toPlainString());
    }
    return new UpdatePricingResponse(priceFloors.getOrDefault(ctx.user(), "0.25"));
  }

  @Override
  public GetPriceResponse getPrice(RequestContext ctx, GetPriceRequest request)
      throws ServiceException {
    var response = prices.get(new Key(ctx.user(), request.fetchtcgCardId(), request.condition()));
    if (response == null) {
      throw new IllegalStateException("pricing response was not seeded");
    }
    return response;
  }

  @Override
  public CreateRepricingResponse createRepricing(
      RequestContext ctx, CreateRepricingRequest request) {
    repricings.computeIfAbsent(
        ctx.user(),
        user ->
            new GetRepricingResponse(
                RepricingStatus.QUEUED, 0, Instant.now().getEpochSecond(), null));
    return new CreateRepricingResponse();
  }

  @Override
  public GetRepricingResponse getRepricing(RequestContext ctx, GetRepricingRequest request)
      throws ServiceException {
    var response = repricings.get(ctx.user());
    if (response == null) {
      throw new NotFoundException("Not Found");
    }
    return response;
  }

  public void seedPrice(
      String user, String cardId, Condition condition, GetPriceResponse response) {
    prices.put(new Key(user, cardId, condition), response);
  }

  private record Key(String user, String cardId, Condition condition) {}
}
