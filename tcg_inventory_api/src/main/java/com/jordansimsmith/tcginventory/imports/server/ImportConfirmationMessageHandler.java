package com.jordansimsmith.tcginventory.imports.server;

import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.service.ServiceException;
import com.jordansimsmith.tcginventory.inventory.client.Condition;
import com.jordansimsmith.tcginventory.inventory.client.CreateUnitRequest;
import com.jordansimsmith.tcginventory.inventory.client.InventoryService;
import com.jordansimsmith.time.Clock;
import java.math.BigDecimal;
import java.util.List;

public class ImportConfirmationMessageHandler {
  private final ImportReadRepository importReadRepository;
  private final ImportMutationRepository importMutationRepository;
  private final InventoryService inventoryService;
  private final Clock clock;

  public ImportConfirmationMessageHandler(
      ImportReadRepository importReadRepository,
      ImportMutationRepository importMutationRepository,
      InventoryService inventoryService,
      Clock clock) {
    this.importReadRepository = importReadRepository;
    this.importMutationRepository = importMutationRepository;
    this.inventoryService = inventoryService;
    this.clock = clock;
  }

  public void handleMessage(RequestContext ctx, ImportMessage message) throws ServiceException {
    if (message.type() != ImportMessageType.CONFIRM) {
      throw new IllegalArgumentException("message is not a confirmation message");
    }

    var importItem = importReadRepository.getImport(ctx.user(), message.importId());
    if (importItem == null) {
      throw new IllegalStateException("confirmation import not found");
    }
    if ("confirmed".equals(importItem.getStatus())) {
      return;
    }
    if (!"confirming".equals(importItem.getStatus())) {
      throw new IllegalStateException("confirmation import is not in confirming status");
    }

    for (var row : importReadRepository.findRows(ctx.user(), message.importId())) {
      if (!"keep".equals(row.getDecision()) || row.getSkuId() != null) {
        continue;
      }

      var created =
          inventoryService.createUnit(
              ctx,
              new CreateUnitRequest(
                  "import:" + message.importId() + ":row:" + row.getPosition(),
                  importItem.getGame(),
                  row.getExternalId(),
                  row.getFinish(),
                  Condition.valueOf(row.getCondition()),
                  row.getName(),
                  row.getSetCode(),
                  row.getSetName(),
                  row.getCollectorNumber(),
                  row.getSuggestedPrice() == null ? null : new BigDecimal(row.getSuggestedPrice()),
                  row.getFetchtcgCardId(),
                  row.getFetchtcgSetId(),
                  row.getPhotos() == null
                      ? List.of()
                      : row.getPhotos().stream().map(ImportRowItem.Photo::getPhotoId).toList()));
      importMutationRepository.recordCreatedUnit(
          ctx.user(),
          message.importId(),
          row.getPosition(),
          created.skuId(),
          created.sequenceNumber(),
          created.location());
    }

    importMutationRepository.finishConfirmation(ctx.user(), message.importId(), clock.now());
  }
}
