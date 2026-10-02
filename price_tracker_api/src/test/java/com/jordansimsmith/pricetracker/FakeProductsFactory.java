package com.jordansimsmith.pricetracker;

import java.util.ArrayList;
import java.util.List;

public class FakeProductsFactory implements ProductsFactory {
  private final List<Product> chemistWarehouseProducts = new ArrayList<>();
  private final List<Product> nzProteinProducts = new ArrayList<>();
  private final List<Product> sportsfuelProducts = new ArrayList<>();

  @Override
  public List<Product> findProducts() {
    var allProducts = new ArrayList<Product>();
    allProducts.addAll(chemistWarehouseProducts);
    allProducts.addAll(nzProteinProducts);
    allProducts.addAll(sportsfuelProducts);
    return allProducts;
  }

  @Override
  public Product getProduct(String id) {
    return findProducts().stream()
        .filter(product -> product.id().equals(id))
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("Unknown product ID: " + id));
  }

  public void addChemistWarehouseProducts(List<Product> products) {
    chemistWarehouseProducts.addAll(products);
  }

  public void addNzProteinProducts(List<Product> products) {
    nzProteinProducts.addAll(products);
  }

  public void addSportsfuelProducts(List<Product> products) {
    sportsfuelProducts.addAll(products);
  }

  public void reset() {
    chemistWarehouseProducts.clear();
    nzProteinProducts.clear();
    sportsfuelProducts.clear();
  }
}
