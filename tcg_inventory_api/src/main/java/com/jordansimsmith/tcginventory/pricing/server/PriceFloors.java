package com.jordansimsmith.tcginventory.pricing.server;

import java.math.BigDecimal;
import java.math.RoundingMode;
import javax.annotation.Nullable;

public class PriceFloors {
  public static final BigDecimal DEFAULT = new BigDecimal("0.25");

  public static BigDecimal effective(@Nullable String storedPriceFloor) {
    if (storedPriceFloor == null) {
      return DEFAULT;
    }
    try {
      var priceFloor = new BigDecimal(storedPriceFloor);
      if (priceFloor.signum() < 0) {
        throw new IllegalStateException("saved price floor is invalid");
      }
      return priceFloor.setScale(2, RoundingMode.UNNECESSARY);
    } catch (NumberFormatException | ArithmeticException e) {
      throw new IllegalStateException("saved price floor is invalid");
    }
  }
}
