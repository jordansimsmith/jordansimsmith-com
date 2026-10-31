package com.jordansimsmith.tcginventory.inventory.server;

import com.jordansimsmith.service.BadRequestException;
import com.jordansimsmith.service.NotFoundException;
import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.service.ServiceException;
import com.jordansimsmith.tcginventory.inventory.client.GetSkuRequest;
import com.jordansimsmith.tcginventory.inventory.client.GetSkuResponse;
import com.jordansimsmith.tcginventory.inventory.client.Sku;
import java.math.BigDecimal;
import javax.annotation.Nullable;

public class GetSkuService {
  private final InventoryReadRepository inventoryRepository;

  public GetSkuService(InventoryReadRepository inventoryRepository) {
    this.inventoryRepository = inventoryRepository;
  }

  public GetSkuResponse getSku(RequestContext ctx, GetSkuRequest request) throws ServiceException {
    if (request.skuId() == null || request.skuId().isBlank()) {
      throw new BadRequestException("sku_id is required");
    }
    var item = inventoryRepository.getSku(ctx.user(), request.skuId());
    if (item == null) {
      throw new NotFoundException("Not Found");
    }
    return new GetSkuResponse(toSku(item));
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
        item.getCondition(),
        parsePrice(item.getLastPublishedPrice()));
  }

  @Nullable
  private static BigDecimal parsePrice(@Nullable String value) {
    return value == null ? null : new BigDecimal(value);
  }
}
