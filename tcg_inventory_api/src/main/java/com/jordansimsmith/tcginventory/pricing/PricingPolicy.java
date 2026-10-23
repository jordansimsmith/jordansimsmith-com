package com.jordansimsmith.tcginventory.pricing;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashSet;
import java.util.List;
import javax.annotation.Nullable;

public class PricingPolicy {
  private static final BigDecimal PRICE_INCREMENT = new BigDecimal("0.05");
  private static final BigDecimal TICK_RATE = new BigDecimal("0.025");
  private static final BigDecimal DEEP_DISCOUNT_RATE = new BigDecimal("0.80");
  private static final BigDecimal SOLE_SOURCE_PREMIUM_RATE = new BigDecimal("1.15");
  private static final BigDecimal SOLE_SOURCE_MINIMUM_MARKET = new BigDecimal("2.00");
  private static final int SUPPORTED_FLOOR_SELLER_COUNT = 2;

  public BigDecimal calculatePrice(
      BigDecimal marketPrice,
      List<CompetitorPricing.CompetitorPriceTier> competitors,
      BigDecimal priceFloor) {
    var lowestCompetitor = competitors.isEmpty() ? null : competitors.get(0).price();
    var supportedFloor = findSupportedFloor(competitors);
    var benchmark = selectBenchmark(marketPrice, lowestCompetitor, supportedFloor);
    return roundToIncrement(benchmark).max(priceFloor);
  }

  @Nullable
  private BigDecimal findSupportedFloor(List<CompetitorPricing.CompetitorPriceTier> competitors) {
    var cumulativeSellers = new HashSet<String>();
    for (var tier : competitors) {
      cumulativeSellers.addAll(tier.sellerKeys());
      if (cumulativeSellers.size() >= SUPPORTED_FLOOR_SELLER_COUNT) {
        return tier.price();
      }
    }
    return null;
  }

  private BigDecimal selectBenchmark(
      BigDecimal marketPrice,
      @Nullable BigDecimal lowestCompetitor,
      @Nullable BigDecimal supportedFloor) {
    if (lowestCompetitor != null
        && lowestCompetitor.compareTo(marketPrice.multiply(DEEP_DISCOUNT_RATE)) >= 0) {
      var tick = computeTick(lowestCompetitor);
      return lowestCompetitor.subtract(tick);
    }

    if (supportedFloor != null) {
      return supportedFloor;
    }

    if (lowestCompetitor == null && marketPrice.compareTo(SOLE_SOURCE_MINIMUM_MARKET) >= 0) {
      return marketPrice.multiply(SOLE_SOURCE_PREMIUM_RATE);
    }

    return marketPrice;
  }

  private BigDecimal computeTick(BigDecimal lowestCompetitor) {
    var rawTick = lowestCompetitor.multiply(TICK_RATE);
    var roundedTick = roundToIncrement(rawTick);
    return roundedTick.max(PRICE_INCREMENT);
  }

  private BigDecimal roundToIncrement(BigDecimal value) {
    return value.divide(PRICE_INCREMENT, 0, RoundingMode.HALF_UP).multiply(PRICE_INCREMENT);
  }
}
