package com.jordansimsmith.pricetracker;

import javax.annotation.Nullable;
import org.jsoup.nodes.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class NzMusclePriceExtractor implements PriceExtractor {
  private static final Logger LOGGER = LoggerFactory.getLogger(NzMusclePriceExtractor.class);

  @Override
  @Nullable
  public Double extractPrice(Document document) {
    var saleElement = document.selectFirst(".price--show-badge .price-item--sale");
    if (saleElement != null) {
      return parsePrice(saleElement.text(), "sale");
    }

    var regularElement = document.selectFirst(".price--show-badge .price-item--regular");
    if (regularElement != null) {
      return parsePrice(regularElement.text(), "regular");
    }

    LOGGER.warn("nzmuscle price not found using .price--show-badge selectors");
    return null;
  }

  @Nullable
  private Double parsePrice(String rawText, String priceType) {
    var normalized = rawText.replaceAll("[^0-9.]", "");
    if (normalized.isEmpty()) {
      LOGGER.warn("nzmuscle {} price text '{}' contained no digits", priceType, rawText);
      return null;
    }

    try {
      return Double.parseDouble(normalized);
    } catch (NumberFormatException e) {
      LOGGER.warn("nzmuscle {} price text '{}' could not be parsed", priceType, rawText, e);
      return null;
    }
  }
}
