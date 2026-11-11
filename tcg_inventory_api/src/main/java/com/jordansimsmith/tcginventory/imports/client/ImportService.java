package com.jordansimsmith.tcginventory.imports.client;

import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.service.ServiceException;

public interface ImportService {
  FindImportsResponse findImports(RequestContext ctx, FindImportsRequest request)
      throws ServiceException;

  GetImportResponse getImport(RequestContext ctx, GetImportRequest request) throws ServiceException;

  UpdateImportRowResponse updateImportRow(RequestContext ctx, UpdateImportRowRequest request)
      throws ServiceException;

  DeleteImportRowResponse deleteImportRow(RequestContext ctx, DeleteImportRowRequest request)
      throws ServiceException;

  DeleteImportResponse deleteImport(RequestContext ctx, DeleteImportRequest request)
      throws ServiceException;
}
