package com.jordansimsmith.tcginventory.orders.client;

import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.service.ServiceException;

public interface OrderService {
  FindOrdersResponse findOrders(RequestContext ctx, FindOrdersRequest request)
      throws ServiceException;

  GetOrderResponse getOrder(RequestContext ctx, GetOrderRequest request) throws ServiceException;

  GetOrderSettingsResponse getOrderSettings(RequestContext ctx, GetOrderSettingsRequest request)
      throws ServiceException;

  UpdateOrderSettingsResponse updateOrderSettings(
      RequestContext ctx, UpdateOrderSettingsRequest request) throws ServiceException;

  UpdateOrdersResponse updateOrders(RequestContext ctx, UpdateOrdersRequest request)
      throws ServiceException;
}
