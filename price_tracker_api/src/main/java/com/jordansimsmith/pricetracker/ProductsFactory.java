package com.jordansimsmith.pricetracker;

import java.net.URI;
import java.util.List;

public interface ProductsFactory {
  record Product(String id, URI url, String name) {}

  List<Product> findProducts();

  Product getProduct(String id);
}
