package com.jordansimsmith.tcginventory.inventory.client;

import com.jordansimsmith.service.NotFoundException;
import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.service.ServiceException;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class FakeInventoryService implements InventoryService {
  private final Map<Key, List<Unit>> units = new HashMap<>();
  private final Map<SkuKey, Sku> skus = new HashMap<>();

  @Override
  public FindSkusResponse findSkus(RequestContext ctx, FindSkusRequest request) {
    var search = request.search() == null ? "" : request.search().toLowerCase(Locale.ROOT);
    var matches =
        skus.entrySet().stream()
            .filter(entry -> entry.getKey().user().equals(ctx.user()))
            .map(Map.Entry::getValue)
            .filter(sku -> sku.game().equals(request.game()))
            .filter(sku -> sku.name().toLowerCase(Locale.ROOT).startsWith(search))
            .sorted(
                Comparator.comparing(
                    sku ->
                        sku.game() + "#" + sku.name().toLowerCase(Locale.ROOT) + "#" + sku.skuId()))
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

  public void seedUnits(String user, String skuId, List<Unit> units) {
    this.units.put(
        new Key(user, skuId),
        units.stream().sorted(Comparator.comparingInt(Unit::sequenceNumber)).toList());
  }

  public void seedSku(String user, Sku sku) {
    skus.put(new SkuKey(user, sku.skuId()), sku);
  }

  private record Key(String user, String skuId) {}

  private record SkuKey(String user, String skuId) {}
}
