package com.jordansimsmith.pricetracker;

import com.google.common.base.Preconditions;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;

public class FakeChemistWarehouseClient implements ChemistWarehouseClient {
  private final Map<URI, Double> prices = new HashMap<>();

  @Override
  public double getPrice(URI url) {
    var price = prices.get(url);
    Preconditions.checkNotNull(price);
    return price;
  }

  public void setPrice(URI url, double price) {
    prices.put(url, price);
  }

  public void reset() {
    prices.clear();
  }
}
