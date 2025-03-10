package com.jordansimsmith.pricetracker;

import javax.annotation.Nullable;
import org.jsoup.nodes.Document;

public class ChemistWarehousePriceExtractor implements PriceExtractor {
  @Override
  @Nullable
  public Double extractPrice(Document document) {
    var element = document.select(".product_details .Price .product__price");
    if (element.isEmpty()) {
      return null;
    }

    try {
      return Double.parseDouble(element.text().replaceAll("\\$", ""));
    } catch (NumberFormatException e) {
      return null;
    }
  }
}
