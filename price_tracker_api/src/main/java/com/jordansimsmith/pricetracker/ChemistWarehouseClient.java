package com.jordansimsmith.pricetracker;

import java.net.URI;

public interface ChemistWarehouseClient {
  double getPrice(URI url);
}
