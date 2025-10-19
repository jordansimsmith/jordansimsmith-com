package com.jordansimsmith.pricetracker;

import javax.annotation.Nullable;
import org.jsoup.nodes.Document;

public class NzMusclePriceExtractor implements PriceExtractor {
  @Override
  @Nullable
  public Double extractPrice(Document document) {
    var element = document.select(".price-item--regular");
    if (element.isEmpty()) {
      return null;
    }

    try {
      return Double.parseDouble(element.text().replaceAll("\\$", "").trim());
    } catch (NumberFormatException e) {
      return null;
    }
  }
}
