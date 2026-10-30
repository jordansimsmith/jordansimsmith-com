package com.jordansimsmith.tcginventory.inventory.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jordansimsmith.dynamodb.Continuations;
import com.jordansimsmith.service.BadRequestException;
import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.tcginventory.inventory.client.FindUnitsRequest;
import com.jordansimsmith.tcginventory.inventory.client.FindUnitsResponse;
import com.jordansimsmith.tcginventory.inventory.client.Unit;
import com.jordansimsmith.tcginventory.inventory.client.UnitStatus;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import javax.annotation.Nullable;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

public class FindUnitsService {
  private static final int DEFAULT_LIMIT = 100;
  private static final int MAX_LIMIT = 100;
  private final InventoryReadRepository inventoryRepository;
  private final ObjectMapper objectMapper;

  public FindUnitsService(InventoryReadRepository inventoryRepository, ObjectMapper objectMapper) {
    this.inventoryRepository = inventoryRepository;
    this.objectMapper = objectMapper;
  }

  public FindUnitsResponse findUnits(RequestContext ctx, FindUnitsRequest request)
      throws BadRequestException {
    if (request.skuId() == null || request.skuId().isBlank()) {
      throw new BadRequestException("sku_id is required");
    }
    var limit = request.limit() == null ? DEFAULT_LIMIT : request.limit();
    if (limit < 1 || limit > MAX_LIMIT) {
      throw new BadRequestException("limit must be between 1 and 100");
    }

    var continuation = decodeContinuation(request.continuation());
    var page = inventoryRepository.findUnits(ctx.user(), request.skuId(), limit, continuation);
    if (page == null) {
      return new FindUnitsResponse(List.of(), null);
    }

    var units =
        page.items().stream()
            .filter(item -> !"removed".equals(item.getStatus()))
            .map(
                item ->
                    new Unit(
                        item.getSequenceNumber(),
                        formatLocation(item.getSequenceNumber()),
                        UnitStatus.valueOf(item.getStatus().toUpperCase(Locale.ROOT)),
                        item.getPhotos() == null
                            ? List.of()
                            : item.getPhotos().stream()
                                .map(InventoryUnitItem.PhotoItem::getPhotoId)
                                .toList(),
                        Objects.requireNonNull(
                                item.getCreatedAt(), "Inventory unit has no created_at")
                            .getEpochSecond(),
                        item.getUpdatedAt() == null ? null : item.getUpdatedAt().getEpochSecond()))
            .toList();
    var nextContinuation = Continuations.encode(page.lastEvaluatedKey(), objectMapper);
    return new FindUnitsResponse(units, nextContinuation);
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

  private static String formatLocation(int sequenceNumber) {
    var blockNumber = sequenceNumber / 100;
    var offset = sequenceNumber % 100;
    var letter = (char) ('A' + blockNumber / 100);
    return "" + letter + blockNumber % 100 + "-" + offset;
  }
}
