package com.jordansimsmith.tcginventory.inventory.client;

import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.service.ServiceException;

public interface InventoryService {
  FindUnitsResponse findUnits(RequestContext ctx, FindUnitsRequest request) throws ServiceException;
}
