package com.jordansimsmith.pricetracker;

import javax.annotation.Nullable;
import org.jsoup.nodes.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ChemistWarehousePriceExtractor implements PriceExtractor {
  private static final Logger LOGGER =
      LoggerFactory.getLogger(ChemistWarehousePriceExtractor.class);

  @Override
  @Nullable
  public Double extractPrice(Document document) {
    var element = document.selectFirst(".product_details .Price .product__price");
    if (element == null) {
      LOGGER.warn("chemist warehouse price not found with product__price selector");
      return null;
    }

    var normalized = element.text().replaceAll("[^0-9.]", "");
    if (normalized.isEmpty()) {
      LOGGER.warn("chemist warehouse price text '{}' contained no digits", element.text());
      return null;
    }

    try {
      return Double.parseDouble(normalized);
    } catch (NumberFormatException e) {
      LOGGER.warn("chemist warehouse price text '{}' could not be parsed", element.text(), e);
      return null;
    }
  }
}
