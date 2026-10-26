package com.jordansimsmith.tcginventory.pricing;

import java.math.BigDecimal;
import java.math.RoundingMode;
import javax.annotation.Nullable;

public class Pricing {
  public static final BigDecimal DEFAULT_PRICE_FLOOR = new BigDecimal("0.25");

  public static BigDecimal marketPrice(@Nullable BigDecimal marketPrice) {
    return (marketPrice == null ? BigDecimal.ZERO : marketPrice).setScale(2, RoundingMode.HALF_UP);
  }
}
