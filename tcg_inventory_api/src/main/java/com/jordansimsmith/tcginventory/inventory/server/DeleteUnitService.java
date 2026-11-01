package com.jordansimsmith.tcginventory.inventory.server;

import com.jordansimsmith.service.BadRequestException;
import com.jordansimsmith.service.ConflictException;
import com.jordansimsmith.service.NotFoundException;
import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.service.ServiceException;
import com.jordansimsmith.tcginventory.inventory.client.DeleteUnitRequest;
import com.jordansimsmith.tcginventory.inventory.client.DeleteUnitResponse;

public class DeleteUnitService {
  private final InventoryReadRepository inventoryReadRepository;
  private final InventoryMutationRepository inventoryMutationRepository;

  public DeleteUnitService(
      InventoryReadRepository inventoryReadRepository,
      InventoryMutationRepository inventoryMutationRepository) {
    this.inventoryReadRepository = inventoryReadRepository;
    this.inventoryMutationRepository = inventoryMutationRepository;
  }

  public DeleteUnitResponse deleteUnit(RequestContext ctx, DeleteUnitRequest request)
      throws ServiceException {
    if (request.skuId() == null || request.skuId().isBlank()) {
      throw new BadRequestException("sku_id is required");
    }
    if (request.sequenceNumber() < 0) {
      throw new BadRequestException("sequence_number must be nonnegative");
    }

    var unit =
        inventoryReadRepository.getUnit(ctx.user(), request.skuId(), request.sequenceNumber());
    if (unit == null) {
      throw new NotFoundException("Not Found");
    }

    switch (unit.getStatus()) {
      case "removed" -> {
        return new DeleteUnitResponse();
      }
      case "in_stock" -> {}
      case "reserved", "sold" -> throw new ConflictException("unit is not in stock");
      default -> throw new IllegalStateException("Inventory unit has an unknown status");
    }

    inventoryMutationRepository.deleteUnit(
        ctx.user(), request.skuId(), request.sequenceNumber(), request.reason());
    return new DeleteUnitResponse();
  }
}
