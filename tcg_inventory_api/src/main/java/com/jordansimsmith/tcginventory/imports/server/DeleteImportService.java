package com.jordansimsmith.tcginventory.imports.server;

import com.jordansimsmith.service.BadRequestException;
import com.jordansimsmith.service.ConflictException;
import com.jordansimsmith.service.NotFoundException;
import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.service.ServiceException;
import com.jordansimsmith.tcginventory.imports.client.DeleteImportRequest;
import com.jordansimsmith.tcginventory.imports.client.DeleteImportResponse;

public class DeleteImportService {
  private final ImportReadRepository importReadRepository;
  private final ImportMutationRepository importMutationRepository;

  public DeleteImportService(
      ImportReadRepository importReadRepository,
      ImportMutationRepository importMutationRepository) {
    this.importReadRepository = importReadRepository;
    this.importMutationRepository = importMutationRepository;
  }

  public DeleteImportResponse deleteImport(RequestContext ctx, DeleteImportRequest request)
      throws ServiceException {
    checkRequest(request);
    var user = ctx.user();
    var importItem = importReadRepository.getImport(user, request.importId());
    if (importItem == null) {
      throw new NotFoundException("Not Found");
    }
    if (!"review".equals(importItem.getStatus())) {
      throw new ConflictException("import is not in a deletable status");
    }

    importMutationRepository.deleteImport(user, request.importId());
    var rows = importReadRepository.findRows(user, request.importId());
    importMutationRepository.deleteRows(rows);
    return new DeleteImportResponse();
  }

  private static void checkRequest(DeleteImportRequest request) throws BadRequestException {
    if (request.importId() == null || request.importId().isBlank()) {
      throw new BadRequestException("import_id is required");
    }
  }
}
