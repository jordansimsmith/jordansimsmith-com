package com.jordansimsmith.pricetracker;

import java.net.URI;
import javax.annotation.Nullable;
import javax.inject.Singleton;

@Singleton
public class FakeNzProteinClient implements NzProteinClient {
  private Double price;

  public void setPrice(Double price) {
    this.price = price;
  }

  @Override
  @Nullable
  public Double getPrice(URI url) {
    return price;
  }
}
