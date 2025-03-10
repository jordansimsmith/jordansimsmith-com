package com.jordansimsmith.pricetracker;

import javax.annotation.Nullable;
import org.jsoup.nodes.Document;

public interface PriceExtractor {
  /**
   * Extract the price from a Jsoup document
   *
   * @param document The Jsoup document of the product page
   * @return The extracted price or null if not found
   */
  @Nullable
  Double extractPrice(Document document);
}
