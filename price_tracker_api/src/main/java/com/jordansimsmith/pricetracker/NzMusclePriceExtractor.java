package com.jordansimsmith.pricetracker;

import javax.annotation.Nullable;
import org.jsoup.nodes.Document;

public class NzMusclePriceExtractor implements PriceExtractor {
  @Override
  @Nullable
  public Double extractPrice(Document document) {
    var saleElement = document.select(".price-item--sale");
    if (!saleElement.isEmpty()) {
      try {
        return Double.parseDouble(saleElement.text().replaceAll("\\$", "").trim());
      } catch (NumberFormatException e) {
        return null;
      }
    }

    var regularElement = document.select(".price-item--regular");
    if (regularElement.isEmpty()) {
      return null;
    }

    try {
      return Double.parseDouble(regularElement.text().replaceAll("\\$", "").trim());
    } catch (NumberFormatException e) {
      return null;
    }
  }
}
