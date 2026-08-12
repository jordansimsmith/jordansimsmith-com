package com.jordansimsmith.tcginventory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.annotation.Nullable;

public class PricingPolicy {
  private static final BigDecimal KEEP_THRESHOLD = new BigDecimal("0.25");
  private static final BigDecimal PRICE_INCREMENT = new BigDecimal("0.05");
  private static final BigDecimal PRICE_FLOOR = new BigDecimal("0.25");
  private static final BigDecimal TICK_RATE = new BigDecimal("0.025");
  private static final BigDecimal DEEP_DISCOUNT_RATE = new BigDecimal("0.80");
  private static final BigDecimal SOLE_SOURCE_PREMIUM_RATE = new BigDecimal("1.15");
  private static final BigDecimal SOLE_SOURCE_MINIMUM_MARKET = new BigDecimal("2.00");
  private static final int SUPPORTED_FLOOR_SELLER_COUNT = 2;

  public enum Decision {
    KEEP,
    DISCARD
  }

  public record RivalTier(BigDecimal price, Set<String> sellerKeys) {}

  public record PricingResult(Decision decision, @Nullable BigDecimal suggestedPrice) {}

  public PricingResult appraise(BigDecimal marketPrice, List<RivalTier> rivals) {
    if (marketPrice.compareTo(KEEP_THRESHOLD) < 0) {
      return new PricingResult(Decision.DISCARD, null);
    }

    var lowestRival = rivals.isEmpty() ? null : rivals.get(0).price();
    var supportedFloor = findSupportedFloor(rivals);
    var benchmark = selectBenchmark(marketPrice, lowestRival, supportedFloor);
    var price = roundToIncrement(benchmark).max(PRICE_FLOOR);

    return new PricingResult(Decision.KEEP, price);
  }

  @Nullable
  private BigDecimal findSupportedFloor(List<RivalTier> rivals) {
    var cumulativeSellers = new HashSet<String>();
    for (var tier : rivals) {
      cumulativeSellers.addAll(tier.sellerKeys());
      if (cumulativeSellers.size() >= SUPPORTED_FLOOR_SELLER_COUNT) {
        return tier.price();
      }
    }
    return null;
  }

  private BigDecimal selectBenchmark(
      BigDecimal marketPrice,
      @Nullable BigDecimal lowestRival,
      @Nullable BigDecimal supportedFloor) {
    if (lowestRival != null
        && lowestRival.compareTo(marketPrice.multiply(DEEP_DISCOUNT_RATE)) >= 0) {
      var tick = computeTick(lowestRival);
      return lowestRival.subtract(tick);
    }

    if (supportedFloor != null) {
      return supportedFloor;
    }

    if (lowestRival == null && marketPrice.compareTo(SOLE_SOURCE_MINIMUM_MARKET) >= 0) {
      return marketPrice.multiply(SOLE_SOURCE_PREMIUM_RATE);
    }

    return marketPrice;
  }

  private BigDecimal computeTick(BigDecimal lowestRival) {
    var rawTick = lowestRival.multiply(TICK_RATE);
    var roundedTick = roundToIncrement(rawTick);
    return roundedTick.max(PRICE_INCREMENT);
  }

  private BigDecimal roundToIncrement(BigDecimal value) {
    return value.divide(PRICE_INCREMENT, 0, RoundingMode.HALF_UP).multiply(PRICE_INCREMENT);
  }
}
