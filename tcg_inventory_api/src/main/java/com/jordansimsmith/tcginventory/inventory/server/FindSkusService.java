package com.jordansimsmith.tcginventory.inventory.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jordansimsmith.dynamodb.Continuations;
import com.jordansimsmith.service.BadRequestException;
import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.service.ServiceException;
import com.jordansimsmith.tcginventory.inventory.client.FindSkusRequest;
import com.jordansimsmith.tcginventory.inventory.client.FindSkusResponse;
import com.jordansimsmith.tcginventory.inventory.client.Sku;
import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.annotation.Nullable;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

public class FindSkusService {
  private static final int DEFAULT_LIMIT = 20;
  private static final int MAX_LIMIT = 100;
  private final InventoryReadRepository inventoryRepository;
  private final ObjectMapper objectMapper;

  public FindSkusService(InventoryReadRepository inventoryRepository, ObjectMapper objectMapper) {
    this.inventoryRepository = inventoryRepository;
    this.objectMapper = objectMapper;
  }

  public FindSkusResponse findSkus(RequestContext ctx, FindSkusRequest request)
      throws ServiceException {
    if (request.game() == null || request.game().isBlank()) {
      throw new BadRequestException("game is required");
    }
    var limit = request.limit() == null ? DEFAULT_LIMIT : request.limit();
    if (limit < 1 || limit > MAX_LIMIT) {
      throw new BadRequestException("limit must be between 1 and 100");
    }
    var continuation = decodeContinuation(request.continuation());
    var search = request.search() == null ? "" : request.search().toLowerCase(Locale.ROOT);
    var page =
        inventoryRepository.findSkus(ctx.user(), request.game(), search, limit, continuation);
    if (page == null) {
      return new FindSkusResponse(List.of(), null);
    }
    var skus = page.items().stream().map(FindSkusService::toSku).toList();
    return new FindSkusResponse(skus, Continuations.encode(page.lastEvaluatedKey(), objectMapper));
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
        item.getLastPublishedPrice() == null ? null : new BigDecimal(item.getLastPublishedPrice()));
  }

  @Nullable
  private Map<String, AttributeValue> decodeContinuation(@Nullable String continuation)
      throws BadRequestException {
    if (continuation == null || continuation.isEmpty()) {
      return null;
    }
    try {
      return Continuations.decode(continuation, objectMapper);
    } catch (RuntimeException e) {
      throw new BadRequestException("Invalid continuation");
    }
  }
}
