package com.jordansimsmith.tcginventory.orders.server;

import java.math.BigDecimal;
import java.util.List;
import javax.annotation.Nullable;

public class OrderRows {
  @Nullable
  public static String itemsTotalPrice(List<OrderRowItem> rows) {
    if (rows.isEmpty()) {
      return null;
    }
    var total = BigDecimal.ZERO;
    for (var row : rows) {
      if (row.getPrice() != null) {
        total = total.add(new BigDecimal(row.getPrice()));
      }
    }
    return total.toPlainString();
  }

  @Nullable
  public static String listedTotalPrice(List<OrderRowItem> rows) {
    if (rows.isEmpty()) {
      return null;
    }
    var total = BigDecimal.ZERO;
    for (var row : rows) {
      if (row.getListedPrice() == null) {
        return null;
      }
      total =
          total.add(
              new BigDecimal(row.getListedPrice()).multiply(BigDecimal.valueOf(row.getQuantity())));
    }
    return total.toPlainString();
  }
}
