package com.jordansimsmith.tcginventory.orders;

import java.math.BigDecimal;
import java.util.List;
import javax.annotation.Nullable;

public class OrderLines {
  public static BigDecimal itemsTotal(List<OrderItem.OrderLine> lines) {
    var total = BigDecimal.ZERO;
    for (var line : lines) {
      if (line.getPrice() != null) {
        total = total.add(new BigDecimal(line.getPrice()));
      }
    }
    return total;
  }

  @Nullable
  public static String itemsTotalPrice(List<OrderItem.OrderLine> lines) {
    if (lines.isEmpty()) {
      return null;
    }
    return itemsTotal(lines).toPlainString();
  }

  @Nullable
  public static String listedTotalPrice(List<OrderItem.OrderLine> lines) {
    if (lines.isEmpty()) {
      return null;
    }
    var total = BigDecimal.ZERO;
    for (var line : lines) {
      if (line.getListedPrice() == null) {
        return null;
      }
      total =
          total.add(
              new BigDecimal(line.getListedPrice())
                  .multiply(BigDecimal.valueOf(line.getQuantity())));
    }
    return total.toPlainString();
  }
}
