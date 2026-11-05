package com.jordansimsmith.tcginventory.orders.server;

import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.service.ServiceException;
import com.jordansimsmith.tcginventory.orders.client.GetOrderSettingsRequest;
import com.jordansimsmith.tcginventory.orders.client.GetOrderSettingsResponse;

public class GetOrderSettingsService {
  private final OrderSettingsRepository settingsRepository;

  public GetOrderSettingsService(OrderSettingsRepository settingsRepository) {
    this.settingsRepository = settingsRepository;
  }

  public GetOrderSettingsResponse getOrderSettings(
      RequestContext ctx, GetOrderSettingsRequest request) throws ServiceException {
    var item = settingsRepository.get(ctx.user());
    var trackOrdersAfter =
        item == null || item.getTrackOrdersAfter() == null
            ? null
            : item.getTrackOrdersAfter().getEpochSecond();
    return new GetOrderSettingsResponse(trackOrdersAfter);
  }
}
