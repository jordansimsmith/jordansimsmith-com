package com.jordansimsmith.tcginventory.pricing.client;

import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.service.ServiceException;
import java.util.HashMap;
import java.util.Map;

public class FakePricingService implements PricingService {
  private final Map<Key, GetPriceResponse> prices = new HashMap<>();

  @Override
  public GetPriceResponse getPrice(RequestContext ctx, GetPriceRequest request)
      throws ServiceException {
    var response = prices.get(new Key(ctx.user(), request.fetchtcgCardId(), request.condition()));
    if (response == null) {
      throw new IllegalStateException("pricing response was not seeded");
    }
    return response;
  }

  public void seedPrice(
      String user, String cardId, Condition condition, GetPriceResponse response) {
    prices.put(new Key(user, cardId, condition), response);
  }

  private record Key(String user, String cardId, Condition condition) {}
}
