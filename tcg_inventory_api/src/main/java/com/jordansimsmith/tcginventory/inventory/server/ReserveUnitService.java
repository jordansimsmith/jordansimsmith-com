package com.jordansimsmith.tcginventory.inventory.server;

import com.jordansimsmith.service.BadRequestException;
import com.jordansimsmith.service.ConflictException;
import com.jordansimsmith.service.NotFoundException;
import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.service.ServiceException;
import com.jordansimsmith.tcginventory.inventory.client.ReserveUnitRequest;
import com.jordansimsmith.tcginventory.inventory.client.ReserveUnitResponse;
import java.util.Map;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

public class ReserveUnitService {
  private final InventoryReadRepository readRepository;
  private final InventoryMutationRepository mutationRepository;

  public ReserveUnitService(
      InventoryReadRepository readRepository, InventoryMutationRepository mutationRepository) {
    this.readRepository = readRepository;
    this.mutationRepository = mutationRepository;
  }

  public ReserveUnitResponse reserveUnit(RequestContext ctx, ReserveUnitRequest request)
      throws ServiceException {
    if (request.operationId() == null || request.operationId().isBlank()) {
      throw new BadRequestException("operation_id is required");
    }
    var previous = mutationRepository.getReserveUnitOperation(ctx.user(), request.operationId());
    if (previous != null) {
      return new ReserveUnitResponse(previous.getSequenceNumber());
    }

    if (request.reservationId() == null || request.reservationId().isBlank()) {
      throw new BadRequestException("reservation_id is required");
    }
    if (request.skuId() == null || request.skuId().isBlank()) {
      throw new BadRequestException("sku_id is required");
    }
    if (readRepository.getSku(ctx.user(), request.skuId()) == null) {
      throw new NotFoundException("Not Found");
    }

    Map<String, AttributeValue> continuation = null;
    do {
      var page = readRepository.findUnits(ctx.user(), request.skuId(), 100, continuation);
      for (var unit : page.items()) {
        if ("in_stock".equals(unit.getStatus())) {
          var allocation =
              mutationRepository.reserveUnit(
                  ctx.user(),
                  request.operationId(),
                  request.reservationId(),
                  request.skuId(),
                  unit.getSequenceNumber());
          return new ReserveUnitResponse(allocation.getSequenceNumber());
        }
      }
      continuation = page.lastEvaluatedKey();
    } while (continuation != null && !continuation.isEmpty());
    throw new ConflictException("No in-stock units are available for this SKU");
  }
}
