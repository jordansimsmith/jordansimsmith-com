package com.jordansimsmith.pricetracker;

import com.jordansimsmith.localstack.LocalStackContainer;

public class PriceTrackerContainer extends LocalStackContainer<PriceTrackerContainer> {
  public PriceTrackerContainer() {
    super("test.properties", "pricetracker.image.name", "pricetracker.image.loader");
  }
}
