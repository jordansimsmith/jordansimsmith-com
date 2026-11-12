package com.jordansimsmith.tcginventory.imports.server;

import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.service.ServiceException;

public interface AppraisalResolver {
  AppraisalResult resolve(RequestContext ctx, ImportRowItem row) throws ServiceException;
}
