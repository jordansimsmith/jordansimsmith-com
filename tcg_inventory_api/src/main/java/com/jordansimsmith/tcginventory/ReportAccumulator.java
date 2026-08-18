package com.jordansimsmith.tcginventory;

import java.math.BigDecimal;
import java.util.List;

public class ReportAccumulator {
  private BigDecimal inventoryValue = BigDecimal.ZERO;
  private int inStockUnits = 0;
  private int skuCount = 0;
  private int reservedUnits = 0;
  private int soldUnits = 0;
  private BigDecimal revenueToDate = BigDecimal.ZERO;
  private int unpricedUnits = 0;

  public void addSku(TcgInventoryItem sku, List<TcgInventoryItem> units) {
    skuCount++;

    var price = resolvePrice(sku);

    for (var unit : units) {
      var status = unit.getStatus();
      if ("removed".equals(status)) {
        continue;
      }
      switch (status) {
        case "in_stock" -> {
          inStockUnits++;
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

  public ReportTotals toTotals() {
    return new ReportTotals(
        inventoryValue.toPlainString(),
        inStockUnits,
        skuCount,
        reservedUnits,
        soldUnits,
        revenueToDate.toPlainString(),
        unpricedUnits);
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
}
