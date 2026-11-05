package com.jordansimsmith.tcginventory.orders.client;

import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.service.ServiceException;

public interface OrderService {
  GetOrderSettingsResponse getOrderSettings(RequestContext ctx, GetOrderSettingsRequest request)
      throws ServiceException;

  UpdateOrderSettingsResponse updateOrderSettings(
      RequestContext ctx, UpdateOrderSettingsRequest request) throws ServiceException;
}
