package com.jordansimsmith.tcginventory.orders.server;

import com.jordansimsmith.service.NotFoundException;
import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.service.ServiceException;
import com.jordansimsmith.tcginventory.inventory.client.FindUnitLocationsRequest;
import com.jordansimsmith.tcginventory.inventory.client.GetSkuRequest;
import com.jordansimsmith.tcginventory.inventory.client.InventoryService;
import com.jordansimsmith.tcginventory.inventory.client.Sku;
import com.jordansimsmith.tcginventory.inventory.client.UnitLocation;
import com.jordansimsmith.tcginventory.inventory.client.UnitReference;
import com.jordansimsmith.tcginventory.orders.client.BuyerAddress;
import com.jordansimsmith.tcginventory.orders.client.DeliveryMode;
import com.jordansimsmith.tcginventory.orders.client.GetOrderRequest;
import com.jordansimsmith.tcginventory.orders.client.GetOrderResponse;
import com.jordansimsmith.tcginventory.orders.client.NeighborCard;
import com.jordansimsmith.tcginventory.orders.client.OrderLine;
import com.jordansimsmith.tcginventory.orders.client.OrderState;
import com.jordansimsmith.tcginventory.orders.client.OrderUnit;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.annotation.Nullable;

public class GetOrderService {
  private static final int LOCATION_BATCH_SIZE = 100;

  private final OrderReadRepository orderReadRepository;
  private final InventoryService inventoryService;

  public GetOrderService(
      OrderReadRepository orderReadRepository, InventoryService inventoryService) {
    this.orderReadRepository = orderReadRepository;
    this.inventoryService = inventoryService;
  }

  public GetOrderResponse getOrder(RequestContext ctx, GetOrderRequest request)
      throws ServiceException {
    var order = getOrderItem(ctx.user(), request.orderId());
    var rows = orderReadRepository.findRows(ctx.user(), order);
    var skuById = new HashMap<String, Sku>();
    var locationByReference = new HashMap<UnitReference, UnitLocation>();

    if ("to_pick".equals(order.getStatus())) {
      locationByReference.putAll(findLocations(ctx, rows, skuById));
    }
    findMissingSkus(ctx, rows, skuById);

    var units = toUnits(rows, skuById, locationByReference, "to_pick".equals(order.getStatus()));
    return new GetOrderResponse(
        order.getOrderId(),
        OrderState.valueOf(order.getStatus().toUpperCase(Locale.ROOT)),
        order.getCreatedAt() == null ? 0 : order.getCreatedAt().getEpochSecond(),
        toDeliveryMode(order.getDeliveryMode()),
        order.getBuyerName(),
        toBuyerAddress(order.getBuyerAddress()),
        order.getPostageOption(),
        order.getTotalPrice(),
        OrderRows.itemsTotalPrice(rows),
        OrderRows.listedTotalPrice(rows),
        units.size(),
        toLines(rows, skuById),
        units);
  }

  private OrderItem getOrderItem(String user, String orderId) throws NotFoundException {
    var order = orderReadRepository.getOrder(user, orderId);
    if (order == null) {
      throw new NotFoundException("Not Found");
    }
    return order;
  }

  private Map<UnitReference, UnitLocation> findLocations(
      RequestContext ctx, List<OrderRowItem> rows, Map<String, Sku> skuById)
      throws ServiceException {
    var references = new ArrayList<UnitReference>();
    for (var row : rows) {
      for (var sequenceNumber : row.getAllocatedSequenceNumbers()) {
        references.add(new UnitReference(row.getSkuId(), sequenceNumber));
      }
    }

    var locations = new ArrayList<UnitLocation>();
    for (var start = 0; start < references.size(); start += LOCATION_BATCH_SIZE) {
      var batch =
          references.subList(start, Math.min(start + LOCATION_BATCH_SIZE, references.size()));
      locations.addAll(
          inventoryService.findUnitLocations(ctx, new FindUnitLocationsRequest(batch)).locations());
    }

    var locationByReference = new HashMap<UnitReference, UnitLocation>();
    for (var location : locations) {
      var reference = new UnitReference(location.sku().skuId(), location.sequenceNumber());
      locationByReference.put(reference, location);
      skuById.put(location.sku().skuId(), location.sku());
      addNeighborSku(skuById, location.previousCard());
      addNeighborSku(skuById, location.nextCard());
    }
    for (var reference : references) {
      if (!locationByReference.containsKey(reference)) {
        throw new IllegalStateException("order allocation has no physical location");
      }
    }
    return locationByReference;
  }

  private void findMissingSkus(
      RequestContext ctx, List<OrderRowItem> rows, Map<String, Sku> skuById)
      throws ServiceException {
    for (var row : rows) {
      if (!skuById.containsKey(row.getSkuId())) {
        skuById.put(
            row.getSkuId(), inventoryService.getSku(ctx, new GetSkuRequest(row.getSkuId())).sku());
      }
    }
  }

  private static List<OrderLine> toLines(List<OrderRowItem> rows, Map<String, Sku> skuById) {
    var lines = new ArrayList<OrderLine>();
    for (var row : rows) {
      var sku = skuById.get(row.getSkuId());
      lines.add(
          new OrderLine(
              sku.game(),
              sku.name(),
              sku.setCode(),
              sku.collectorNumber(),
              sku.finish(),
              sku.condition().name(),
              row.getQuantity(),
              row.getPrice(),
              row.getListedPrice()));
    }
    return lines;
  }

  private static List<OrderUnit> toUnits(
      List<OrderRowItem> rows,
      Map<String, Sku> skuById,
      Map<UnitReference, UnitLocation> locationByReference,
      boolean includeLocations) {
    var units = new ArrayList<OrderUnit>();
    for (var row : rows) {
      var sku = skuById.get(row.getSkuId());
      var unitPrice = perUnitPrice(row);
      for (var sequenceNumber : row.getAllocatedSequenceNumbers()) {
        var location =
            includeLocations
                ? locationByReference.get(new UnitReference(row.getSkuId(), sequenceNumber))
                : null;
        units.add(
            new OrderUnit(
                sku.game(),
                sequenceNumber,
                location == null ? null : location.location(),
                location == null ? null : location.currentLocation(),
                sku.externalId(),
                sku.name(),
                sku.setCode(),
                sku.collectorNumber(),
                sku.finish(),
                sku.condition().name(),
                unitPrice,
                location == null ? null : toNeighborCard(location.previousCard()),
                location == null ? null : toNeighborCard(location.nextCard())));
      }
    }
    units.sort(Comparator.comparing(OrderUnit::game).thenComparingInt(OrderUnit::sequenceNumber));
    return units;
  }

  private static void addNeighborSku(Map<String, Sku> skuById, @Nullable Sku sku) {
    if (sku != null) {
      skuById.put(sku.skuId(), sku);
    }
  }

  @Nullable
  private static DeliveryMode toDeliveryMode(@Nullable String value) {
    return value == null ? null : DeliveryMode.valueOf(value);
  }

  @Nullable
  private static BuyerAddress toBuyerAddress(@Nullable OrderItem.BuyerAddress address) {
    if (address == null) {
      return null;
    }
    return new BuyerAddress(
        address.getLine1(),
        address.getLine2(),
        address.getSuburb(),
        address.getCity(),
        address.getPostCode(),
        address.getCountry());
  }

  private static NeighborCard toNeighborCard(@Nullable Sku sku) {
    if (sku == null) {
      return null;
    }
    return new NeighborCard(
        sku.name(), sku.setCode(), sku.collectorNumber(), sku.finish(), sku.condition().name());
  }

  @Nullable
  private static String perUnitPrice(OrderRowItem line) {
    if (line.getPrice() == null) {
      return null;
    }
    return new BigDecimal(line.getPrice())
        .divide(BigDecimal.valueOf(line.getQuantity()), 2, RoundingMode.HALF_UP)
        .toPlainString();
  }
}
