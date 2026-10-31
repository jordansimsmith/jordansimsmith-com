package com.jordansimsmith.tcginventory.inventory.server;

import com.jordansimsmith.service.BadRequestException;
import com.jordansimsmith.service.NotFoundException;
import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.tcginventory.inventory.client.GetUnitRequest;
import com.jordansimsmith.tcginventory.inventory.client.GetUnitResponse;
import com.jordansimsmith.tcginventory.inventory.client.Unit;
import com.jordansimsmith.tcginventory.inventory.client.UnitStatus;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

public class GetUnitService {
  private final InventoryReadRepository inventoryRepository;

  public GetUnitService(InventoryReadRepository inventoryRepository) {
    this.inventoryRepository = inventoryRepository;
  }

  public GetUnitResponse getUnit(RequestContext ctx, GetUnitRequest request)
      throws BadRequestException, NotFoundException {
    if (request.skuId() == null || request.skuId().isBlank()) {
      throw new BadRequestException("sku_id is required");
    }
    var item = inventoryRepository.getUnit(ctx.user(), request.skuId(), request.sequenceNumber());
    if (item == null || "removed".equals(item.getStatus())) {
      throw new NotFoundException("Not Found");
    }
    return new GetUnitResponse(
        new Unit(
            item.getSequenceNumber(),
            Locations.format(item.getSequenceNumber()),
            UnitStatus.valueOf(item.getStatus().toUpperCase(Locale.ROOT)),
            item.getPhotos() == null
                ? List.of()
                : item.getPhotos().stream().map(InventoryUnitItem.PhotoItem::getPhotoId).toList(),
            Objects.requireNonNull(item.getCreatedAt(), "Inventory unit has no created_at")
                .getEpochSecond(),
            item.getUpdatedAt() == null ? null : item.getUpdatedAt().getEpochSecond()));
  }
}
