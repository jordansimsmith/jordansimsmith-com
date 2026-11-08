package com.jordansimsmith.tcginventory.reports.client;

import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.service.ServiceException;

public interface ReportService {
  CreateReportResponse createReport(RequestContext ctx, CreateReportRequest request)
      throws ServiceException;

  GetReportResponse getReport(RequestContext ctx, GetReportRequest request) throws ServiceException;
}
