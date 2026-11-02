package com.jordansimsmith.tcginventory.inventory.server;

import com.jordansimsmith.service.BadRequestException;
import com.jordansimsmith.service.NotFoundException;
import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.service.ServiceException;
import com.jordansimsmith.tcginventory.inventory.client.UpdateSkuRequest;
import com.jordansimsmith.tcginventory.inventory.client.UpdateSkuResponse;
import java.math.RoundingMode;

public class UpdateSkuService {
  private final InventoryReadRepository inventoryReadRepository;
  private final InventoryMutationRepository inventoryMutationRepository;

  public UpdateSkuService(
      InventoryReadRepository inventoryReadRepository,
      InventoryMutationRepository inventoryMutationRepository) {
    this.inventoryReadRepository = inventoryReadRepository;
    this.inventoryMutationRepository = inventoryMutationRepository;
  }

  public UpdateSkuResponse updateSku(RequestContext ctx, UpdateSkuRequest request)
      throws ServiceException {
    if (request.skuId() == null || request.skuId().isBlank()) {
      throw new BadRequestException("sku_id is required");
    }
    var price = request.price();
    if (price != null && price.signum() < 0) {
      throw new BadRequestException("price must be nonnegative");
    }
    if (price != null) {
      try {
        price = price.setScale(2, RoundingMode.UNNECESSARY);
      } catch (ArithmeticException e) {
        throw new BadRequestException("price must have at most two decimal places");
      }
    }

    var sku = inventoryReadRepository.getSku(ctx.user(), request.skuId());
    if (sku == null) {
      throw new NotFoundException("Not Found");
    }
    if (price != null) {
      inventoryMutationRepository.updateSkuPrice(
          ctx.user(), request.skuId(), sku.getSuggestedPrice(), price.toPlainString());
    }
    return new UpdateSkuResponse();
  }
}
