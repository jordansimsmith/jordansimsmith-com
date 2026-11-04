package com.jordansimsmith.tcginventory.inventory.server;

import com.jordansimsmith.service.BadRequestException;
import com.jordansimsmith.service.ConflictException;
import com.jordansimsmith.service.NotFoundException;
import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.service.ServiceException;
import com.jordansimsmith.tcginventory.inventory.client.SellUnitRequest;
import com.jordansimsmith.tcginventory.inventory.client.SellUnitResponse;

public class SellUnitService {
  private final InventoryReadRepository readRepository;
  private final InventoryMutationRepository mutationRepository;

  public SellUnitService(
      InventoryReadRepository readRepository, InventoryMutationRepository mutationRepository) {
    this.readRepository = readRepository;
    this.mutationRepository = mutationRepository;
  }

  public SellUnitResponse sellUnit(RequestContext ctx, SellUnitRequest request)
      throws ServiceException {
    if (request.skuId() == null || request.skuId().isBlank()) {
      throw new BadRequestException("sku_id is required");
    }
    if (request.sequenceNumber() < 0) {
      throw new BadRequestException("sequence_number must be nonnegative");
    }
    if (request.reservationId() == null || request.reservationId().isBlank()) {
      throw new BadRequestException("reservation_id is required");
    }

    var reservation =
        mutationRepository.getReservation(
            ctx.user(), request.reservationId(), request.skuId(), request.sequenceNumber());
    if (reservation != null) {
      switch (reservation.getStatus()) {
        case "sold" -> {
          return new SellUnitResponse();
        }
        case "released" -> throw new ConflictException("reservation has already been released");
        case "reserved" -> {}
        default -> throw new IllegalStateException("Reservation has an unknown status");
      }
    }

    var unit = readRepository.getUnit(ctx.user(), request.skuId(), request.sequenceNumber());
    if (unit == null) {
      throw new NotFoundException("Not Found");
    }
    if (!"reserved".equals(unit.getStatus())
        || !request.reservationId().equals(unit.getReservationId())) {
      throw new ConflictException("unit is not reserved for this reservation");
    }

    mutationRepository.sellUnit(
        ctx.user(), request.reservationId(), request.skuId(), request.sequenceNumber());
    return new SellUnitResponse();
  }
}
