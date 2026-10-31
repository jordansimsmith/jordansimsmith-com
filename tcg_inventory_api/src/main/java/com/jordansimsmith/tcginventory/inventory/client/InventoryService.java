package com.jordansimsmith.tcginventory.inventory.client;

import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.service.ServiceException;

public interface InventoryService {
  FindSkusResponse findSkus(RequestContext ctx, FindSkusRequest request) throws ServiceException;

  GetSkuResponse getSku(RequestContext ctx, GetSkuRequest request) throws ServiceException;

  GetUnitResponse getUnit(RequestContext ctx, GetUnitRequest request) throws ServiceException;

  FindUnitsResponse findUnits(RequestContext ctx, FindUnitsRequest request) throws ServiceException;
}
