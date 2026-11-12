package com.jordansimsmith.tcginventory.imports.server;

import java.math.BigDecimal;

public class AppraisalPolicy {
  private static final BigDecimal KEEP_THRESHOLD = new BigDecimal("0.25");

  public enum Decision {
    KEEP,
    DISCARD
  }

  public Decision decide(BigDecimal marketPrice) {
    return marketPrice.compareTo(KEEP_THRESHOLD) < 0 ? Decision.DISCARD : Decision.KEEP;
  }
}
