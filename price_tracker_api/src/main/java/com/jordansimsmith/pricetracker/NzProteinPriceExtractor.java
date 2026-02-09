package com.jordansimsmith.pricetracker;

import javax.annotation.Nullable;
import org.jsoup.nodes.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class NzProteinPriceExtractor implements PriceExtractor {
  private static final Logger LOGGER = LoggerFactory.getLogger(NzProteinPriceExtractor.class);

  @Override
  @Nullable
  public Double extractPrice(Document document) {
    var element = document.selectFirst("[itemprop=\"price\"]");
    if (element == null) {
      LOGGER.warn(
          "nz protein price not found with itemprop=price selector for url '{}'",
          document.location());
      return null;
    }

    var normalized = element.text().replaceAll("[^0-9.]", "");
    if (normalized.isEmpty()) {
      LOGGER.warn(
          "nz protein price text '{}' contained no digits for url '{}'",
          element.text(),
          document.location());
      return null;
    }

    try {
      return Double.parseDouble(normalized);
    } catch (NumberFormatException e) {
      LOGGER.warn(
          "nz protein price text '{}' could not be parsed for url '{}'",
          element.text(),
          document.location(),
          e);
      return null;
    }
  }
}
