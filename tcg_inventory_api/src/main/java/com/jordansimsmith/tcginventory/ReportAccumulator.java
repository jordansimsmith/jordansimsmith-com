package com.jordansimsmith.tcginventory;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ReportAccumulator {
  private static final int TOP_SETS_LIMIT = 10;

  private BigDecimal inventoryValue = BigDecimal.ZERO;
  private int inStockUnits = 0;
  private int skuCount = 0;
  private int reservedUnits = 0;
  private int soldUnits = 0;
  private BigDecimal revenueToDate = BigDecimal.ZERO;
  private int unpricedUnits = 0;

  private final Map<String, SetAccumulator> setMap = new HashMap<>();

  public void addSku(TcgInventoryItem sku, List<TcgInventoryItem> units) {
    skuCount++;

    var price = resolvePrice(sku);

    int skuInStockCount = 0;
    for (var unit : units) {
      var status = unit.getStatus();
      if ("removed".equals(status)) {
        continue;
      }
      switch (status) {
        case "in_stock" -> {
          inStockUnits++;
          skuInStockCount++;
          if (price != null) {
            inventoryValue = inventoryValue.add(price);
          } else {
            unpricedUnits++;
          }
        }
        case "reserved" -> reservedUnits++;
        case "sold" -> soldUnits++;
        default -> {}
      }
    }

    if (skuInStockCount > 0) {
      setMap
          .computeIfAbsent(sku.getSetCode(), k -> new SetAccumulator(sku.getSetName()))
          .addUnits(skuInStockCount);
    }
  }

  public void addOrder(TcgInventoryItem order) {
    var status = order.getStatus();
    if (!"to_pick".equals(status) && !"fulfilled".equals(status)) {
      return;
    }
    if (order.getTotalPrice() != null) {
      revenueToDate = revenueToDate.add(new BigDecimal(order.getTotalPrice()));
    }
  }

  public ReportPayload.Totals toTotals() {
    return new ReportPayload.Totals(
        inventoryValue.toPlainString(),
        inStockUnits,
        skuCount,
        reservedUnits,
        soldUnits,
        revenueToDate.toPlainString(),
        unpricedUnits);
  }

  public List<ReportPayload.TopSet> toTopSets() {
    return setMap.entrySet().stream()
        .sorted(
            Comparator.<Map.Entry<String, SetAccumulator>>comparingInt(
                    e -> e.getValue().inStockUnits)
                .reversed()
                .thenComparing(e -> e.getValue().setName))
        .limit(TOP_SETS_LIMIT)
        .map(
            e ->
                new ReportPayload.TopSet(
                    e.getKey(), e.getValue().setName, e.getValue().inStockUnits))
        .toList();
  }

  static BigDecimal resolvePrice(TcgInventoryItem sku) {
    if (sku.getLastPublishedPrice() != null) {
      return new BigDecimal(sku.getLastPublishedPrice());
    }
    if (sku.getSuggestedPrice() != null) {
      return new BigDecimal(sku.getSuggestedPrice());
    }
    return null;
  }

  private static class SetAccumulator {
    final String setName;
    int inStockUnits;

    SetAccumulator(String setName) {
      this.setName = setName;
    }

    void addUnits(int count) {
      inStockUnits += count;
    }
  }
}
