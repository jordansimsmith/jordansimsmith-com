package com.jordansimsmith.tcginventory.inventory.client;

import com.jordansimsmith.service.BadRequestException;
import com.jordansimsmith.service.ConflictException;
import com.jordansimsmith.service.NotFoundException;
import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.service.ServiceException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class FakeInventoryService implements InventoryService {
  private final Map<Key, List<Unit>> units = new HashMap<>();
  private final Map<SkuKey, Sku> skus = new HashMap<>();
  private final Set<SkuKey> dirtySkus = new HashSet<>();

  @Override
  public FindSkusResponse findSkus(RequestContext ctx, FindSkusRequest request)
      throws ServiceException {
    if (request.dirty() == null) {
      if (request.game() != null && request.game().isBlank()) {
        throw new BadRequestException("game is required");
      }
      if (request.game() == null && request.search() != null && !request.search().isBlank()) {
        throw new BadRequestException("search requires game");
      }
    } else if (request.game() != null || request.search() != null) {
      throw new BadRequestException("dirty cannot be combined with game or search");
    }
    var search = request.search() == null ? "" : request.search().toLowerCase(Locale.ROOT);
    var matches =
        skus.entrySet().stream()
            .filter(entry -> entry.getKey().user().equals(ctx.user()))
            .map(Map.Entry::getValue)
            .filter(
                sku ->
                    request.dirty() == null
                        || dirtySkus.contains(new SkuKey(ctx.user(), sku.skuId()))
                            == request.dirty())
            .filter(
                sku ->
                    request.dirty() != null
                        || request.game() == null
                        || sku.game().equals(request.game()))
            .filter(
                sku ->
                    request.dirty() != null
                        || sku.name().toLowerCase(Locale.ROOT).startsWith(search))
            .sorted(
                request.dirty() == null
                    ? Comparator.comparing(
                        sku ->
                            sku.game()
                                + "#"
                                + sku.name().toLowerCase(Locale.ROOT)
                                + "#"
                                + sku.skuId())
                    : Comparator.comparing(Sku::skuId))
            .toList();
    var limit = request.limit() == null ? 20 : request.limit();
    var offset = request.continuation() == null ? 0 : Integer.parseInt(request.continuation());
    var end = Math.min(offset + limit, matches.size());
    return new FindSkusResponse(
        matches.subList(offset, end), end < matches.size() ? Integer.toString(end) : null);
  }

  @Override
  public GetSkuResponse getSku(RequestContext ctx, GetSkuRequest request) throws ServiceException {
    var sku = skus.get(new SkuKey(ctx.user(), request.skuId()));
    if (sku == null) {
      throw new NotFoundException("Not Found");
    }
    return new GetSkuResponse(sku);
  }

  @Override
  public GetUnitResponse getUnit(RequestContext ctx, GetUnitRequest request)
      throws ServiceException {
    var unit =
        units.getOrDefault(new Key(ctx.user(), request.skuId()), List.of()).stream()
            .filter(candidate -> candidate.sequenceNumber() == request.sequenceNumber())
            .filter(candidate -> candidate.status() != UnitStatus.REMOVED)
            .findFirst()
            .orElseThrow(() -> new NotFoundException("Not Found"));
    return new GetUnitResponse(unit);
  }

  @Override
  public FindUnitsResponse findUnits(RequestContext ctx, FindUnitsRequest request)
      throws ServiceException {
    var allUnits = units.getOrDefault(new Key(ctx.user(), request.skuId()), List.of());
    var limit = request.limit() == null ? 100 : request.limit();
    var offset = request.continuation() == null ? 0 : Integer.parseInt(request.continuation());
    var end = Math.min(offset + limit, allUnits.size());
    var page = allUnits.subList(offset, end);
    return new FindUnitsResponse(page, end < allUnits.size() ? Integer.toString(end) : null);
  }

  @Override
  public DeleteUnitResponse deleteUnit(RequestContext ctx, DeleteUnitRequest request)
      throws ServiceException {
    var key = new Key(ctx.user(), request.skuId());
    var allUnits = units.getOrDefault(key, List.of());
    var unit =
        allUnits.stream()
            .filter(candidate -> candidate.sequenceNumber() == request.sequenceNumber())
            .findFirst()
            .orElseThrow(() -> new NotFoundException("Not Found"));
    if (unit.status() == UnitStatus.REMOVED) {
      return new DeleteUnitResponse();
    }
    if (unit.status() != UnitStatus.IN_STOCK) {
      throw new ConflictException("unit is not in stock");
    }
    var removed =
        new Unit(
            unit.sequenceNumber(),
            unit.location(),
            UnitStatus.REMOVED,
            unit.photos(),
            unit.createdAt(),
            Instant.now().getEpochSecond(),
            unit.reservationId());
    units.put(
        key,
        allUnits.stream()
            .map(
                candidate ->
                    candidate.sequenceNumber() == request.sequenceNumber() ? removed : candidate)
            .toList());
    dirtySkus.add(new SkuKey(ctx.user(), request.skuId()));
    return new DeleteUnitResponse();
  }

  @Override
  public UpdateUnitResponse updateUnit(RequestContext ctx, UpdateUnitRequest request)
      throws ServiceException {
    var sourceKey = new SkuKey(ctx.user(), request.skuId());
    var sourceSku = skus.get(sourceKey);
    if (sourceSku == null) {
      throw new NotFoundException("Not Found");
    }
    var sourceUnitsKey = new Key(ctx.user(), request.skuId());
    var sourceUnits = units.getOrDefault(sourceUnitsKey, List.of());
    var unit =
        sourceUnits.stream()
            .filter(candidate -> candidate.sequenceNumber() == request.sequenceNumber())
            .findFirst()
            .orElseThrow(() -> new NotFoundException("Not Found"));
    if (unit.status() != UnitStatus.IN_STOCK) {
      throw new ConflictException("unit is not in stock");
    }
    if (request.condition() == null || sourceSku.condition() == request.condition()) {
      return new UpdateUnitResponse(request.skuId());
    }
    var parts = request.skuId().split("#");
    var destinationSkuId =
        String.join("#", Arrays.copyOf(parts, parts.length - 1)) + "#" + request.condition();
    var destinationKey = new SkuKey(ctx.user(), destinationSkuId);
    skus.putIfAbsent(
        destinationKey,
        new Sku(
            destinationSkuId,
            sourceSku.game(),
            sourceSku.externalId(),
            sourceSku.name(),
            sourceSku.setCode(),
            sourceSku.setName(),
            sourceSku.collectorNumber(),
            sourceSku.finish(),
            request.condition(),
            sourceSku.price(),
            sourceSku.fetchtcgCardId(),
            sourceSku.fetchtcgSetId(),
            sourceSku.version()));
    units.put(
        sourceUnitsKey,
        sourceUnits.stream()
            .filter(candidate -> candidate.sequenceNumber() != request.sequenceNumber())
            .toList());
    var destinationUnitsKey = new Key(ctx.user(), destinationSkuId);
    var destinationUnits = new ArrayList<>(units.getOrDefault(destinationUnitsKey, List.of()));
    destinationUnits.add(unit);
    destinationUnits.sort(Comparator.comparingInt(Unit::sequenceNumber));
    units.put(destinationUnitsKey, List.copyOf(destinationUnits));
    dirtySkus.add(sourceKey);
    dirtySkus.add(destinationKey);
    return new UpdateUnitResponse(destinationSkuId);
  }

  @Override
  public UpdateSkuResponse updateSku(RequestContext ctx, UpdateSkuRequest request)
      throws ServiceException {
    if (request.skuId() == null || request.skuId().isBlank()) {
      throw new BadRequestException("sku_id is required");
    }
    if (request.price() != null && request.dirty() != null) {
      throw new BadRequestException("price and dirty cannot be updated together");
    }
    if (request.dirty() != null && request.version() == null) {
      throw new BadRequestException("version is required when updating dirty state");
    }
    if (request.dirty() == null && request.version() != null) {
      throw new BadRequestException("version requires a dirty update");
    }
    var key = new SkuKey(ctx.user(), request.skuId());
    var sku = skus.get(key);
    if (sku == null) {
      throw new NotFoundException("Not Found");
    }
    if (request.dirty() != null) {
      if (request.version() != sku.version()) {
        return new UpdateSkuResponse();
      }
      if (request.dirty()) {
        skus.put(
            key,
            new Sku(
                sku.skuId(),
                sku.game(),
                sku.externalId(),
                sku.name(),
                sku.setCode(),
                sku.setName(),
                sku.collectorNumber(),
                sku.finish(),
                sku.condition(),
                sku.price(),
                sku.fetchtcgCardId(),
                sku.fetchtcgSetId(),
                sku.version() + 1));
        dirtySkus.add(key);
      } else {
        dirtySkus.remove(key);
      }
      return new UpdateSkuResponse();
    }
    if (request.price() != null) {
      if (request.price().signum() < 0) {
        throw new BadRequestException("price must be nonnegative");
      }
      BigDecimal price;
      try {
        price = request.price().setScale(2, RoundingMode.UNNECESSARY);
      } catch (ArithmeticException e) {
        throw new BadRequestException("price must have at most two decimal places");
      }
      skus.put(
          key,
          new Sku(
              sku.skuId(),
              sku.game(),
              sku.externalId(),
              sku.name(),
              sku.setCode(),
              sku.setName(),
              sku.collectorNumber(),
              sku.finish(),
              sku.condition(),
              price,
              sku.fetchtcgCardId(),
              sku.fetchtcgSetId(),
              sku.version() + 1));
      dirtySkus.add(key);
    }
    return new UpdateSkuResponse();
  }

  public void seedUnits(String user, String skuId, List<Unit> units) {
    this.units.put(
        new Key(user, skuId),
        units.stream().sorted(Comparator.comparingInt(Unit::sequenceNumber)).toList());
  }

  public void seedSku(String user, Sku sku, boolean dirty) {
    var key = new SkuKey(user, sku.skuId());
    skus.put(key, sku);
    if (dirty) {
      dirtySkus.add(key);
    } else {
      dirtySkus.remove(key);
    }
  }

  private record Key(String user, String skuId) {}

  private record SkuKey(String user, String skuId) {}
}
