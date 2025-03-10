package com.jordansimsmith.pricetracker;

import java.net.URI;
import javax.annotation.Nullable;

public interface PriceClient {
  /**
   * Get the price for a product at the given URL
   *
   * @param url The URL of the product page
   * @return The price of the product, or null if the price could not be determined
   */
  @Nullable
  Double getPrice(URI url);
}
