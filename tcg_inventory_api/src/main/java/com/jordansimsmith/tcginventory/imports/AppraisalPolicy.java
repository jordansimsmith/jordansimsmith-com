package com.jordansimsmith.tcginventory.imports;

import java.math.BigDecimal;
import javax.annotation.Nullable;

public class AppraisalPolicy {
  private static final BigDecimal KEEP_THRESHOLD = new BigDecimal("0.25");

  public enum Decision {
    KEEP,
    DISCARD
  }

  public record AppraisalResult(Decision decision, @Nullable BigDecimal suggestedPrice) {}

  public AppraisalResult appraise(BigDecimal marketPrice, BigDecimal suggestedPrice) {
    if (marketPrice.compareTo(KEEP_THRESHOLD) < 0) {
      return new AppraisalResult(Decision.DISCARD, null);
    }

    return new AppraisalResult(Decision.KEEP, suggestedPrice);
  }
}
