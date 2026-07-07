package com.jordansimsmith.pricetracker;

import javax.annotation.Nullable;
import org.jsoup.nodes.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SportsfuelPriceExtractor implements PriceExtractor {
  private static final Logger LOGGER = LoggerFactory.getLogger(SportsfuelPriceExtractor.class);

  @Override
  @Nullable
  public Double extractPrice(Document document) {
    var element = document.selectFirst(".product-info__price .price__default .price__current");
    if (element == null) {
      LOGGER.warn(
          "sportsfuel price not found with price__current selector for url '{}'",
          document.location());
      return null;
    }

    var normalized = element.text().replaceAll("[^0-9.]", "");
    if (normalized.isEmpty()) {
      LOGGER.warn(
          "sportsfuel price text '{}' contained no digits for url '{}'",
          element.text(),
          document.location());
      return null;
    }

    try {
      return Double.parseDouble(normalized);
    } catch (NumberFormatException e) {
      LOGGER.warn(
          "sportsfuel price text '{}' could not be parsed for url '{}'",
          element.text(),
          document.location(),
          e);
      return null;
    }
  }
}
