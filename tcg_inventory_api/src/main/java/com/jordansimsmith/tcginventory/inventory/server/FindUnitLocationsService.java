package com.jordansimsmith.tcginventory.inventory.server;

import com.jordansimsmith.service.BadRequestException;
import com.jordansimsmith.service.NotFoundException;
import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.service.ServiceException;
import com.jordansimsmith.tcginventory.inventory.client.Condition;
import com.jordansimsmith.tcginventory.inventory.client.FindUnitLocationsRequest;
import com.jordansimsmith.tcginventory.inventory.client.FindUnitLocationsResponse;
import com.jordansimsmith.tcginventory.inventory.client.Sku;
import com.jordansimsmith.tcginventory.inventory.client.UnitLocation;
import com.jordansimsmith.tcginventory.inventory.client.UnitReference;
import com.jordansimsmith.tcginventory.inventory.client.UnitStatus;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;

public class FindUnitLocationsService {
  private final InventoryReadRepository inventoryRepository;

  public FindUnitLocationsService(InventoryReadRepository inventoryRepository) {
    this.inventoryRepository = inventoryRepository;
  }

  public FindUnitLocationsResponse findUnitLocations(
      RequestContext ctx, FindUnitLocationsRequest request) throws ServiceException {
    checkRequest(request);
    var requestedSkus = findRequestedSkus(ctx, request.units());
    var blocks = toBlocks(request.units(), requestedSkus);
    var blockUnits = findBlockUnits(ctx, blocks);
    return new FindUnitLocationsResponse(toLocations(request.units(), requestedSkus, blockUnits));
  }

  private void checkRequest(FindUnitLocationsRequest request) throws BadRequestException {
    if (request.units() == null) {
      throw new BadRequestException("units is required");
    }
    if (request.units().size() > 100) {
      throw new BadRequestException("units must contain at most 100 entries");
    }
    for (var unit : request.units()) {
      if (unit == null) {
        throw new BadRequestException("units must not contain null");
      }
      if (unit.skuId() == null || unit.skuId().isBlank()) {
        throw new BadRequestException("sku_id is required");
      }
      if (unit.sequenceNumber() < 0) {
        throw new BadRequestException("sequence_number must be nonnegative");
      }
    }
  }

  private List<InventorySkuItem> findRequestedSkus(
      RequestContext ctx, List<UnitReference> references) throws NotFoundException {
    var requestedSkus = new ArrayList<InventorySkuItem>();
    for (var reference : references) {
      var sku = inventoryRepository.getSku(ctx.user(), reference.skuId());
      if (sku == null) {
        throw new NotFoundException("Not Found");
      }
      requestedSkus.add(sku);
    }
    return requestedSkus;
  }

  private Set<GameBlock> toBlocks(
      List<UnitReference> references, List<InventorySkuItem> requestedSkus) {
    var blocks = new LinkedHashSet<GameBlock>();
    for (var index = 0; index < references.size(); index++) {
      var reference = references.get(index);
      var sku = requestedSkus.get(index);
      blocks.add(new GameBlock(sku.getGame(), reference.sequenceNumber() / 100));
    }
    return blocks;
  }

  private Map<GameBlock, List<InventoryUnitItem>> findBlockUnits(
      RequestContext ctx, Set<GameBlock> blocks) {
    var blockUnits = new HashMap<GameBlock, List<InventoryUnitItem>>();
    for (var block : blocks) {
      var units = new ArrayList<InventoryUnitItem>();
      for (var unit :
          inventoryRepository.findBlockUnits(ctx.user(), block.game(), block.blockNumber())) {
        var status = UnitStatus.valueOf(unit.getStatus().toUpperCase(Locale.ROOT));
        if (status == UnitStatus.IN_STOCK || status == UnitStatus.RESERVED) {
          units.add(unit);
        }
      }
      blockUnits.put(block, units);
    }
    return blockUnits;
  }

  private List<UnitLocation> toLocations(
      List<UnitReference> references,
      List<InventorySkuItem> requestedSkus,
      Map<GameBlock, List<InventoryUnitItem>> blockUnits) {
    var locations = new ArrayList<UnitLocation>();
    for (var index = 0; index < references.size(); index++) {
      var location = toLocation(references.get(index), requestedSkus.get(index), blockUnits);
      if (location != null) {
        locations.add(location);
      }
    }
    return locations;
  }

  @Nullable
  private UnitLocation toLocation(
      UnitReference reference,
      InventorySkuItem sku,
      Map<GameBlock, List<InventoryUnitItem>> blockUnits) {
    var blockNumber = reference.sequenceNumber() / 100;
    var position =
        findPosition(
            blockUnits.get(new GameBlock(sku.getGame(), blockNumber)), reference.sequenceNumber());
    if (position == null) {
      return null;
    }
    return new UnitLocation(
        toSku(sku),
        reference.sequenceNumber(),
        Locations.format(reference.sequenceNumber()),
        Locations.format(blockNumber, position.offset()),
        toNeighborSku(position.previous()),
        toNeighborSku(position.next()));
  }

  @Nullable
  private Position findPosition(List<InventoryUnitItem> units, int sequenceNumber) {
    var positionIndex = 0;
    while (positionIndex < units.size()
        && units.get(positionIndex).getSequenceNumber() < sequenceNumber) {
      positionIndex++;
    }

    if (positionIndex == units.size()
        || units.get(positionIndex).getSequenceNumber() != sequenceNumber) {
      return null;
    }

    return new Position(
        positionIndex, unitAt(units, positionIndex - 1), unitAt(units, positionIndex + 1));
  }

  @Nullable
  private InventoryUnitItem unitAt(List<InventoryUnitItem> units, int index) {
    return index < 0 || index >= units.size() ? null : units.get(index);
  }

  @Nullable
  private Sku toNeighborSku(@Nullable InventoryUnitItem unit) {
    if (unit == null) {
      return null;
    }
    var skuItem = inventoryRepository.getSkuForUnit(unit);
    if (skuItem == null) {
      throw new IllegalStateException("Inventory unit references a missing SKU");
    }
    return toSku(skuItem);
  }

  private static Sku toSku(InventorySkuItem item) {
    return new Sku(
        item.getSkuId(),
        item.getGame(),
        item.getExternalId(),
        item.getName(),
        item.getSetCode(),
        item.getSetName(),
        item.getCollectorNumber(),
        item.getFinish(),
        Condition.valueOf(item.getCondition()),
        item.getSuggestedPrice() == null ? null : new BigDecimal(item.getSuggestedPrice()),
        item.getFetchtcgCardId(),
        item.getFetchtcgSetId(),
        item.getVersion() == null ? 0 : item.getVersion());
  }

  private record GameBlock(String game, int blockNumber) {}

  private record Position(
      int offset, @Nullable InventoryUnitItem previous, @Nullable InventoryUnitItem next) {}
}
