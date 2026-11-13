package com.jordansimsmith.tcginventory.scans.client;

import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.service.ServiceException;

public interface ScanService {
  CreateScanResponse createScan(RequestContext ctx, CreateScanRequest request)
      throws ServiceException;

  FindScansResponse findScans(RequestContext ctx, FindScansRequest request) throws ServiceException;

  GetScanResponse getScan(RequestContext ctx, GetScanRequest request) throws ServiceException;

  IdentifyScanResponse identifyScan(RequestContext ctx, IdentifyScanRequest request)
      throws ServiceException;

  ConfirmScanResponse confirmScan(RequestContext ctx, ConfirmScanRequest request)
      throws ServiceException;

  DeleteScanRowResponse deleteScanRow(RequestContext ctx, DeleteScanRowRequest request)
      throws ServiceException;

  DeleteScanResponse deleteScan(RequestContext ctx, DeleteScanRequest request)
      throws ServiceException;
}
