package com.jordansimsmith.pricetracker;

import java.net.URI;
import javax.annotation.Nullable;

public interface ChemistWarehouseClient {
  @Nullable
  Double getPrice(URI url);
}
