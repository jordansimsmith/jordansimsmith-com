package com.jordansimsmith.pricetracker;

import java.util.ArrayList;
import java.util.List;

public class FakeProductsFactory implements ProductsFactory {
  private final List<Product> chemistWarehouseProducts = new ArrayList<>();

  @Override
  public List<Product> findChemistWarehouseProducts() {
    return List.copyOf(chemistWarehouseProducts);
  }

  public void addProducts(List<Product> products) {
    chemistWarehouseProducts.addAll(products);
  }

  public void reset() {
    chemistWarehouseProducts.clear();
  }
}
