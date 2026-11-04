package com.jordansimsmith.tcginventory.inventory.server;

import com.jordansimsmith.service.BadRequestException;
import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.service.ServiceException;
import com.jordansimsmith.tcginventory.games.client.GamesService;
import com.jordansimsmith.tcginventory.games.client.GetGameRequest;
import com.jordansimsmith.tcginventory.inventory.client.CreateUnitRequest;
import com.jordansimsmith.tcginventory.inventory.client.CreateUnitResponse;
import java.math.RoundingMode;

public class CreateUnitService {
  private final InventoryReadRepository readRepository;
  private final InventoryMutationRepository mutationRepository;
  private final GamesService gamesService;

  public CreateUnitService(
      InventoryReadRepository readRepository,
      InventoryMutationRepository mutationRepository,
      GamesService gamesService) {
    this.readRepository = readRepository;
    this.mutationRepository = mutationRepository;
    this.gamesService = gamesService;
  }

  public CreateUnitResponse createUnit(RequestContext ctx, CreateUnitRequest request)
      throws ServiceException {
    if (request.operationId() == null || request.operationId().isBlank()) {
      throw new BadRequestException("operation_id is required");
    }

    var previous = mutationRepository.getUnitCreation(ctx.user(), request.operationId());
    if (previous != null) {
      return new CreateUnitResponse(
          previous.getSkuId(), previous.getSequenceNumber(), previous.getLocation());
    }

    var fields =
        new String[] {
          request.game(),
          request.externalId(),
          request.finish(),
          request.name(),
          request.setCode(),
          request.setName(),
          request.collectorNumber()
        };
    for (var field : fields) {
      if (field == null || field.isBlank()) {
        throw new BadRequestException("unit identity and metadata are required");
      }
    }

    if (request.condition() == null) {
      throw new BadRequestException("condition is required");
    }

    if (request.photos() == null
        || request.photos().stream().anyMatch(id -> id == null || id.isBlank())) {
      throw new BadRequestException("photos must contain nonblank photo IDs");
    }

    var price = request.price();
    if (price != null) {
      if (price.signum() < 0) {
        throw new BadRequestException("price must be nonnegative");
      }
      try {
        price = price.setScale(2, RoundingMode.UNNECESSARY);
      } catch (ArithmeticException e) {
        throw new BadRequestException("price must have at most two decimal places");
      }
    }

    var game = gamesService.getGame(ctx, new GetGameRequest(request.game())).game();
    if (game.finishes().stream().noneMatch(finish -> finish.id().equals(request.finish()))) {
      throw new BadRequestException("unsupported finish for game " + game.id());
    }

    var skuId = SkuIds.format(game, request.externalId(), request.finish(), request.condition());
    var existing = readRepository.getSku(ctx.user(), skuId);

    var sku =
        InventorySkuItem.create(
            ctx.user(),
            skuId,
            request.game(),
            request.externalId(),
            request.finish(),
            request.condition().name(),
            request.name(),
            request.setCode(),
            request.setName(),
            request.collectorNumber(),
            price == null ? null : price.toPlainString(),
            request.fetchtcgCardId(),
            request.fetchtcgSetId());

    var created =
        mutationRepository.createUnit(
            ctx.user(), request.operationId(), sku, request.photos(), existing == null);

    return new CreateUnitResponse(
        created.getSkuId(), created.getSequenceNumber(), created.getLocation());
  }
}
