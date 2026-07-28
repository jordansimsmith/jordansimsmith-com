package com.jordansimsmith.pricetracker;

import javax.annotation.Nullable;
import org.jsoup.nodes.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class VivobarefootPriceExtractor implements PriceExtractor {
  private static final Logger LOGGER = LoggerFactory.getLogger(VivobarefootPriceExtractor.class);

  @Override
  @Nullable
  public Double extractPrice(Document document) {
    var element = document.selectFirst(".price__sale-value");
    if (element == null) {
      LOGGER.warn(
          "vivobarefoot price not found with price__sale-value selector for url '{}'",
          document.location());
      return null;
    }

    var normalized = element.text().replaceAll("[^0-9.]", "");
    if (normalized.isEmpty()) {
      LOGGER.warn(
          "vivobarefoot price text '{}' contained no digits for url '{}'",
          element.text(),
          document.location());
      return null;
    }

    try {
      return Double.parseDouble(normalized);
    } catch (NumberFormatException e) {
      LOGGER.warn(
          "vivobarefoot price text '{}' could not be parsed for url '{}'",
          element.text(),
          document.location(),
          e);
      return null;
    }
  }
}
