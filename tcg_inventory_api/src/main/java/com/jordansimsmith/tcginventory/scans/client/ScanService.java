package com.jordansimsmith.tcginventory.scans.client;

import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.service.ServiceException;

public interface ScanService {
  FindScansResponse findScans(RequestContext ctx, FindScansRequest request) throws ServiceException;

  GetScanResponse getScan(RequestContext ctx, GetScanRequest request) throws ServiceException;
}
