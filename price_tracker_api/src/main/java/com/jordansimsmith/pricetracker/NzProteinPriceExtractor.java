package com.jordansimsmith.pricetracker;

import javax.annotation.Nullable;
import org.jsoup.nodes.Document;

public class NzProteinPriceExtractor implements PriceExtractor {
  @Override
  @Nullable
  public Double extractPrice(Document document) {
    var element = document.select("[itemprop=\"price\"]");
    if (element.isEmpty()) {
      return null;
    }

    try {
      return Double.parseDouble(element.text());
    } catch (NumberFormatException e) {
      return null;
    }
  }
}
