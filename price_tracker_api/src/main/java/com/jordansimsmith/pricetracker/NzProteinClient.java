package com.jordansimsmith.pricetracker;

import java.net.URI;
import javax.annotation.Nullable;

public interface NzProteinClient {
  @Nullable
  Double getPrice(URI url);
}
