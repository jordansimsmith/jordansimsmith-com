package com.jordansimsmith.tcginventory;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class PricingPolicyTest {
  private PricingPolicy policy;

  @BeforeEach
  void setUp() {
    policy = new PricingPolicy();
  }

  @Test
  void appraiseShouldDiscardWhenMarketPriceBelowThreshold() {
    // act
    var result = policy.appraise(new BigDecimal("0.24"), List.of());

    // assert
    assertThat(result.decision()).isEqualTo(PricingPolicy.Decision.DISCARD);
    assertThat(result.suggestedPrice()).isNull();
  }

  @Test
  void appraiseShouldKeepWhenMarketPriceAtThreshold() {
    // act
    var result = policy.appraise(new BigDecimal("0.25"), List.of());

    // assert
    assertThat(result.decision()).isEqualTo(PricingPolicy.Decision.KEEP);
    assertThat(result.suggestedPrice()).isEqualByComparingTo("0.25");
  }

  @Test
  void appraiseShouldRoundToNearestFiveCentsNoRivals() {
    assertPriceWithNoRivals("0.25", "0.25");
    assertPriceWithNoRivals("0.74", "0.75");
    assertPriceWithNoRivals("0.76", "0.75");
    assertPriceWithNoRivals("0.87", "0.85");
    assertPriceWithNoRivals("0.875", "0.90");
    assertPriceWithNoRivals("0.88", "0.90");
    assertPriceWithNoRivals("1.00", "1.00");
  }

  @Test
  void appraiseShouldApplySoleSourcePremiumAndRound() {
    assertPriceWithNoRivals("1.99", "2.00");
    assertPriceWithNoRivals("2.00", "2.30");
    assertPriceWithNoRivals("3.20", "3.70");
  }

  private void assertPriceWithNoRivals(String marketPrice, String expectedPrice) {
    var result = policy.appraise(new BigDecimal(marketPrice), List.of());
    assertThat(result.decision()).isEqualTo(PricingPolicy.Decision.KEEP);
    assertThat(result.suggestedPrice()).isEqualByComparingTo(expectedPrice);
  }

  @Test
  void appraiseShouldUndercutLowestSaneRival() {
    // arrange
    var rivals = List.of(new PricingPolicy.RivalTier(new BigDecimal("5.00"), Set.of("alpha")));

    // act
    var result = policy.appraise(new BigDecimal("5.00"), rivals);

    // assert
    assertThat(result.decision()).isEqualTo(PricingPolicy.Decision.KEEP);
    // tick = max(0.05, round(5.00 * 0.025, 0.05)) = max(0.05, 0.15) = 0.15
    // benchmark = 5.00 - 0.15 = 4.85
    assertThat(result.suggestedPrice()).isEqualByComparingTo("4.85");
  }

  @Test
  void appraiseShouldNotUndercutDeepDiscountRivalWithoutSupport() {
    // arrange
    // rival at 0.40, market 1.00: 0.40 < 80% * 1.00 = 0.80 -> deep discount
    var rivals = List.of(new PricingPolicy.RivalTier(new BigDecimal("0.40"), Set.of("alpha")));

    // act
    var result = policy.appraise(new BigDecimal("1.00"), rivals);

    // assert
    assertThat(result.decision()).isEqualTo(PricingPolicy.Decision.KEEP);
    assertThat(result.suggestedPrice()).isEqualByComparingTo("1.00");
  }

  @Test
  void appraiseShouldUseTwoSellerSupportedFloor() {
    // arrange
    // market 1.40, rivals: 0.40 (alpha, deep discount), 0.76 (beta)
    // supported floor = 0.76 (first price where 2 distinct sellers cumulative)
    var rivals =
        List.of(
            new PricingPolicy.RivalTier(new BigDecimal("0.40"), Set.of("alpha")),
            new PricingPolicy.RivalTier(new BigDecimal("0.76"), Set.of("beta")));

    // act
    var result = policy.appraise(new BigDecimal("1.40"), rivals);

    // assert
    assertThat(result.decision()).isEqualTo(PricingPolicy.Decision.KEEP);
    // benchmark = supported floor 0.76, rounded to 0.75
    assertThat(result.suggestedPrice()).isEqualByComparingTo("0.75");
  }

  @Test
  void appraiseShouldNotCountSameSellerAcrossTiersAsSupport() {
    // arrange
    // same seller (alpha) at two price points doesn't constitute two-seller support
    var rivals =
        List.of(
            new PricingPolicy.RivalTier(new BigDecimal("0.40"), Set.of("alpha")),
            new PricingPolicy.RivalTier(new BigDecimal("0.50"), Set.of("alpha")));

    // act
    var result = policy.appraise(new BigDecimal("1.01"), rivals);

    // assert
    assertThat(result.decision()).isEqualTo(PricingPolicy.Decision.KEEP);
    // no support, deep discount -> falls through to market
    assertThat(result.suggestedPrice()).isEqualByComparingTo("1.00");
  }

  @Test
  void appraiseShouldApplySoleSourcePremiumWhenNoRivalsAndMarketAboveThreshold() {
    // act
    var result = policy.appraise(new BigDecimal("2.00"), List.of());

    // assert
    assertThat(result.decision()).isEqualTo(PricingPolicy.Decision.KEEP);
    // 2.00 * 1.15 = 2.30
    assertThat(result.suggestedPrice()).isEqualByComparingTo("2.30");
  }

  @Test
  void appraiseShouldNotApplySoleSourcePremiumWhenMarketBelowThreshold() {
    // act
    var result = policy.appraise(new BigDecimal("1.99"), List.of());

    // assert
    assertThat(result.decision()).isEqualTo(PricingPolicy.Decision.KEEP);
    // 1.99 rounds to 2.00 (nearest 0.05)
    assertThat(result.suggestedPrice()).isEqualByComparingTo("2.00");
  }

  @Test
  void appraiseShouldEnforceFloorOnLowBenchmark() {
    // arrange
    // rival at 0.25 with market 0.30 -> tick = max(0.05, round(0.25*0.025)) = 0.05
    // benchmark = 0.25 - 0.05 = 0.20 -> floored to 0.25
    var rivals = List.of(new PricingPolicy.RivalTier(new BigDecimal("0.25"), Set.of("alpha")));

    // act
    var result = policy.appraise(new BigDecimal("0.30"), rivals);

    // assert
    assertThat(result.decision()).isEqualTo(PricingPolicy.Decision.KEEP);
    assertThat(result.suggestedPrice()).isEqualByComparingTo("0.25");
  }

  @Test
  void appraiseShouldComputeTickCorrectlyForSmallRival() {
    // arrange
    // rival at 1.00: tick = max(0.05, round(1.00 * 0.025, 0.05)) = max(0.05, 0.05) = 0.05
    // benchmark = 1.00 - 0.05 = 0.95
    var rivals = List.of(new PricingPolicy.RivalTier(new BigDecimal("1.00"), Set.of("alpha")));

    // act
    var result = policy.appraise(new BigDecimal("1.00"), rivals);

    // assert
    assertThat(result.suggestedPrice()).isEqualByComparingTo("0.95");
  }

  @Test
  void appraiseShouldComputeTickCorrectlyForLargeRival() {
    // arrange
    // rival at 10.00: tick = max(0.05, round(10.00 * 0.025, 0.05)) = max(0.05, 0.25) = 0.25
    // benchmark = 10.00 - 0.25 = 9.75
    var rivals = List.of(new PricingPolicy.RivalTier(new BigDecimal("10.00"), Set.of("alpha")));

    // act
    var result = policy.appraise(new BigDecimal("10.00"), rivals);

    // assert
    assertThat(result.suggestedPrice()).isEqualByComparingTo("9.75");
  }
}
