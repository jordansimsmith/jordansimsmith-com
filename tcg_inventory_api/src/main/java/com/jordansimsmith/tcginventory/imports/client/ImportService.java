package com.jordansimsmith.tcginventory.imports.client;

import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.service.ServiceException;

public interface ImportService {
  FindImportsResponse findImports(RequestContext ctx, FindImportsRequest request)
      throws ServiceException;
}
