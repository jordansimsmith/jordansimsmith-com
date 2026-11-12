package com.jordansimsmith.tcginventory.imports.server;

import com.jordansimsmith.queue.QueueClient;
import com.jordansimsmith.service.BadRequestException;
import com.jordansimsmith.service.ConflictException;
import com.jordansimsmith.service.NotFoundException;
import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.service.ServiceException;
import com.jordansimsmith.tcginventory.imports.client.ConfirmImportRequest;
import com.jordansimsmith.tcginventory.imports.client.ConfirmImportResponse;
import com.jordansimsmith.time.Clock;

public class ConfirmImportService {
  private final ImportReadRepository importReadRepository;
  private final ImportMutationRepository importMutationRepository;
  private final QueueClient<ImportMessage> importsQueue;
  private final Clock clock;

  public ConfirmImportService(
      ImportReadRepository importReadRepository,
      ImportMutationRepository importMutationRepository,
      QueueClient<ImportMessage> importsQueue,
      Clock clock) {
    this.importReadRepository = importReadRepository;
    this.importMutationRepository = importMutationRepository;
    this.importsQueue = importsQueue;
    this.clock = clock;
  }

  public ConfirmImportResponse confirmImport(RequestContext ctx, ConfirmImportRequest request)
      throws ServiceException {
    if (request.importId() == null || request.importId().isBlank()) {
      throw new BadRequestException("import_id is required");
    }
    var user = ctx.user();
    var importItem = importReadRepository.getImport(user, request.importId());
    if (importItem == null) {
      throw new NotFoundException("Not Found");
    }
    if ("confirming".equals(importItem.getStatus()) || "confirmed".equals(importItem.getStatus())) {
      return new ConfirmImportResponse();
    }
    if (!"review".equals(importItem.getStatus())) {
      throw new ConflictException("import is not in review status");
    }

    var rowsNeedingPhotos =
        importReadRepository.findRows(user, request.importId()).stream()
            .filter(
                row ->
                    ImportPhotoPolicy.needsPhotos(
                        row.getDecision(),
                        row.getSuggestedPrice(),
                        row.getPhotos() == null ? 0 : row.getPhotos().size()))
            .count();
    if (rowsNeedingPhotos > 0) {
      throw new ConflictException(rowsNeedingPhotos + " rows need photos before confirm");
    }

    var now = clock.now();
    importMutationRepository.startConfirmation(user, request.importId(), now);
    importsQueue.send(
        new ImportMessage(user, request.importId(), ImportMessageType.CONFIRM),
        request.importId(),
        request.importId() + "#confirm");
    return new ConfirmImportResponse();
  }
}
