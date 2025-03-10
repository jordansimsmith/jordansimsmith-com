package com.jordansimsmith.pricetracker;

import java.util.ArrayList;
import java.util.List;

public class FakeProductsFactory implements ProductsFactory {
  private final List<Product> chemistWarehouseProducts = new ArrayList<>();
  private final List<Product> nzProteinProducts = new ArrayList<>();

  @Override
  public List<Product> findChemistWarehouseProducts() {
    return List.copyOf(chemistWarehouseProducts);
  }

  @Override
  public List<Product> findNzProteinProducts() {
    return List.copyOf(nzProteinProducts);
  }

  public void addChemistWarehouseProducts(List<Product> products) {
    chemistWarehouseProducts.addAll(products);
  }

  public void addNzProteinProducts(List<Product> products) {
    nzProteinProducts.addAll(products);
  }

  public void reset() {
    chemistWarehouseProducts.clear();
    nzProteinProducts.clear();
  }
}
