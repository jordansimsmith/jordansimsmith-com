package com.jordansimsmith.tcginventory.inventory.client;

import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.service.ServiceException;

public interface InventoryService {
  FindSkusResponse findSkus(RequestContext ctx, FindSkusRequest request) throws ServiceException;

  GetSkuResponse getSku(RequestContext ctx, GetSkuRequest request) throws ServiceException;

  GetUnitResponse getUnit(RequestContext ctx, GetUnitRequest request) throws ServiceException;

  FindUnitsResponse findUnits(RequestContext ctx, FindUnitsRequest request) throws ServiceException;

  DeleteUnitResponse deleteUnit(RequestContext ctx, DeleteUnitRequest request)
      throws ServiceException;

  UpdateUnitResponse updateUnit(RequestContext ctx, UpdateUnitRequest request)
      throws ServiceException;

  UpdateSkuResponse updateSku(RequestContext ctx, UpdateSkuRequest request) throws ServiceException;
}
