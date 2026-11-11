package com.jordansimsmith.tcginventory.imports.server;

import com.jordansimsmith.service.BadRequestException;
import com.jordansimsmith.service.ConflictException;
import com.jordansimsmith.service.NotFoundException;
import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.service.ServiceException;
import com.jordansimsmith.tcginventory.imports.client.UpdateImportRowRequest;
import com.jordansimsmith.tcginventory.imports.client.UpdateImportRowResponse;

public class UpdateImportRowService {
  private final ImportReadRepository importReadRepository;
  private final ImportMutationRepository importMutationRepository;

  public UpdateImportRowService(
      ImportReadRepository importReadRepository,
      ImportMutationRepository importMutationRepository) {
    this.importReadRepository = importReadRepository;
    this.importMutationRepository = importMutationRepository;
  }

  public UpdateImportRowResponse updateImportRow(RequestContext ctx, UpdateImportRowRequest request)
      throws ServiceException {
    checkRequest(request);
    var user = ctx.user();
    var importItem = importReadRepository.getImport(user, request.importId());
    if (importItem == null) {
      throw new NotFoundException("Not Found");
    }
    if (!"review".equals(importItem.getStatus())) {
      throw new ConflictException("import is not in review status");
    }
    var row = importReadRepository.getRow(user, request.importId(), request.position());
    if (row == null) {
      throw new NotFoundException("Not Found");
    }
    importMutationRepository.updateRowCondition(
        user, request.importId(), request.position(), request.condition().name());
    return new UpdateImportRowResponse();
  }

  private static void checkRequest(UpdateImportRowRequest request) throws BadRequestException {
    if (request.importId() == null || request.importId().isBlank()) {
      throw new BadRequestException("import_id is required");
    }
    if (request.position() <= 0) {
      throw new BadRequestException("position must be positive");
    }
    if (request.condition() == null) {
      throw new BadRequestException("invalid condition");
    }
  }
}
