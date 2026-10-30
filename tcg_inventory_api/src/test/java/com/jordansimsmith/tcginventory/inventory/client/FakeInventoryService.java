package com.jordansimsmith.tcginventory.inventory.client;

import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.service.ServiceException;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class FakeInventoryService implements InventoryService {
  private final Map<Key, List<Unit>> units = new HashMap<>();

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

  private record Key(String user, String skuId) {}
}
