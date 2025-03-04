package com.jordansimsmith.pricetracker;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import javax.annotation.Nullable;

public class FakeChemistWarehouseClient implements ChemistWarehouseClient {
  private final Map<URI, Double> prices = new HashMap<>();

  @Override
  @Nullable
  public Double getPrice(URI url) {
    return prices.get(url);
  }

  public void setPrice(URI url, double price) {
    prices.put(url, price);
  }

  public void reset() {
    prices.clear();
  }
}
