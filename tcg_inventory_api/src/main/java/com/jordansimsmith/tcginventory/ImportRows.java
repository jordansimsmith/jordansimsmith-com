package com.jordansimsmith.tcginventory;

import java.math.BigDecimal;
import java.util.List;

public class ImportRows {
  public static String totalSuggestedPrice(List<ImportRowItem> rows) {
    return rows.stream()
        .filter(row -> "keep".equals(row.getDecision()))
        .map(row -> new BigDecimal(row.getSuggestedPrice()))
        .reduce(new BigDecimal("0.00"), BigDecimal::add)
        .toPlainString();
  }
}
