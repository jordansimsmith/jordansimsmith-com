package com.jordansimsmith.pricetracker;

import java.util.ArrayList;
import java.util.List;

public class FakeProductsFactory implements ProductsFactory {
  private final List<Product> chemistWarehouseProducts = new ArrayList<>();
  private final List<Product> nzProteinProducts = new ArrayList<>();

  @Override
  public List<Product> findProducts() {
    var allProducts = new ArrayList<Product>();
    allProducts.addAll(chemistWarehouseProducts);
    allProducts.addAll(nzProteinProducts);
    return allProducts;
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
