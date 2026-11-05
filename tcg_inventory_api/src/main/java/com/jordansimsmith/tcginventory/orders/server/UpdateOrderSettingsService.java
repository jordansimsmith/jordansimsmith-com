package com.jordansimsmith.tcginventory.orders.server;

import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.tcginventory.orders.client.UpdateOrderSettingsRequest;
import com.jordansimsmith.tcginventory.orders.client.UpdateOrderSettingsResponse;
import java.time.Instant;

public class UpdateOrderSettingsService {
  private final OrderSettingsRepository settingsRepository;

  public UpdateOrderSettingsService(OrderSettingsRepository settingsRepository) {
    this.settingsRepository = settingsRepository;
  }

  public UpdateOrderSettingsResponse updateOrderSettings(
      RequestContext ctx, UpdateOrderSettingsRequest request) {
    if (request.trackOrdersAfter() != null) {
      settingsRepository.update(
          OrderSettingsItem.create(ctx.user(), Instant.ofEpochSecond(request.trackOrdersAfter())));
    }

    return new UpdateOrderSettingsResponse();
  }
}
