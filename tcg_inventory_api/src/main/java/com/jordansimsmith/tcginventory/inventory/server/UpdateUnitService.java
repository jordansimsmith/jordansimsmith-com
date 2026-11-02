package com.jordansimsmith.tcginventory.inventory.server;

import com.jordansimsmith.service.BadRequestException;
import com.jordansimsmith.service.ConflictException;
import com.jordansimsmith.service.NotFoundException;
import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.service.ServiceException;
import com.jordansimsmith.tcginventory.games.client.GamesService;
import com.jordansimsmith.tcginventory.games.client.GetGameRequest;
import com.jordansimsmith.tcginventory.inventory.client.UpdateUnitRequest;
import com.jordansimsmith.tcginventory.inventory.client.UpdateUnitResponse;

public class UpdateUnitService {
  private final InventoryReadRepository inventoryReadRepository;
  private final InventoryMutationRepository inventoryMutationRepository;
  private final GamesService gamesService;

  public UpdateUnitService(
      InventoryReadRepository inventoryReadRepository,
      InventoryMutationRepository inventoryMutationRepository,
      GamesService gamesService) {
    this.inventoryReadRepository = inventoryReadRepository;
    this.inventoryMutationRepository = inventoryMutationRepository;
    this.gamesService = gamesService;
  }

  public UpdateUnitResponse updateUnit(RequestContext ctx, UpdateUnitRequest request)
      throws ServiceException {
    if (request.skuId() == null || request.skuId().isBlank()) {
      throw new BadRequestException("sku_id is required");
    }
    if (request.sequenceNumber() < 0) {
      throw new BadRequestException("sequence_number must be nonnegative");
    }
    var sourceSku = inventoryReadRepository.getSku(ctx.user(), request.skuId());
    if (sourceSku == null) {
      throw new NotFoundException("Not Found");
    }
    var unit =
        inventoryReadRepository.getUnit(ctx.user(), request.skuId(), request.sequenceNumber());
    if (unit == null) {
      throw new NotFoundException("Not Found");
    }
    if (!"in_stock".equals(unit.getStatus())) {
      throw new ConflictException("unit is not in stock");
    }
    var updatedSkuId = request.skuId();
    if (request.condition() != null
        && !request.condition().name().equals(sourceSku.getCondition())) {
      var game = gamesService.getGame(ctx, new GetGameRequest(sourceSku.getGame())).game();
      updatedSkuId =
          SkuIds.format(
              game, sourceSku.getExternalId(), sourceSku.getFinish(), request.condition());
      inventoryMutationRepository.updateUnitCondition(
          ctx.user(), sourceSku, unit, updatedSkuId, request.condition().name());
    }

    // apply additional optional unit updates here before returning the resulting SKU ID
    return new UpdateUnitResponse(updatedSkuId);
  }
}
