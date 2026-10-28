package com.jordansimsmith.tcginventory.pricing.server;

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
  void calculatePriceShouldRoundMarketPriceAndApplySoleSourcePremium() {
    // assert
    assertPrice("0.25", "0.25");
    assertPrice("0.74", "0.75");
    assertPrice("0.76", "0.75");
    assertPrice("0.87", "0.85");
    assertPrice("0.875", "0.90");
    assertPrice("0.88", "0.90");
    assertPrice("1.00", "1.00");
    assertPrice("1.99", "2.00");
    assertPrice("2.00", "2.30");
    assertPrice("3.20", "3.70");
  }

  @Test
  void calculatePriceShouldUndercutLowestSaneCompetitor() {
    // arrange
    var competitors =
        List.of(new CompetitorPricing.CompetitorPriceTier(new BigDecimal("5.00"), Set.of("alpha")));

    // act
    var result =
        policy.calculatePrice(
            new BigDecimal("5.00"), competitors, GetPriceService.DEFAULT_PRICE_FLOOR);

    // assert
    assertThat(result).isEqualByComparingTo("4.85");
  }

  @Test
  void calculatePriceShouldIgnoreDeepDiscountCompetitorWithoutSupport() {
    // arrange
    var competitors =
        List.of(new CompetitorPricing.CompetitorPriceTier(new BigDecimal("0.40"), Set.of("alpha")));

    // act
    var result =
        policy.calculatePrice(
            new BigDecimal("1.00"), competitors, GetPriceService.DEFAULT_PRICE_FLOOR);

    // assert
    assertThat(result).isEqualByComparingTo("1.00");
  }

  @Test
  void calculatePriceShouldUseTwoSellerSupportedFloor() {
    // arrange
    var competitors =
        List.of(
            new CompetitorPricing.CompetitorPriceTier(new BigDecimal("0.40"), Set.of("alpha")),
            new CompetitorPricing.CompetitorPriceTier(new BigDecimal("0.76"), Set.of("beta")));

    // act
    var result =
        policy.calculatePrice(
            new BigDecimal("1.40"), competitors, GetPriceService.DEFAULT_PRICE_FLOOR);

    // assert
    assertThat(result).isEqualByComparingTo("0.75");
  }

  @Test
  void calculatePriceShouldNotCountSameSellerAcrossTiersAsSupport() {
    // arrange
    var competitors =
        List.of(
            new CompetitorPricing.CompetitorPriceTier(new BigDecimal("0.40"), Set.of("alpha")),
            new CompetitorPricing.CompetitorPriceTier(new BigDecimal("0.50"), Set.of("alpha")));

    // act
    var result =
        policy.calculatePrice(
            new BigDecimal("1.01"), competitors, GetPriceService.DEFAULT_PRICE_FLOOR);

    // assert
    assertThat(result).isEqualByComparingTo("1.00");
  }

  @Test
  void calculatePriceShouldApplyFloorAfterBenchmarkRounding() {
    // arrange
    var competitors =
        List.of(new CompetitorPricing.CompetitorPriceTier(new BigDecimal("0.25"), Set.of("alpha")));

    // act
    var defaultFloor =
        policy.calculatePrice(
            new BigDecimal("0.30"), competitors, GetPriceService.DEFAULT_PRICE_FLOOR);
    var lowerFloor =
        policy.calculatePrice(new BigDecimal("0.30"), competitors, new BigDecimal("0.10"));
    var zeroFloor = policy.calculatePrice(new BigDecimal("0.30"), competitors, BigDecimal.ZERO);
    var raisedFloor =
        policy.calculatePrice(new BigDecimal("1.00"), List.of(), new BigDecimal("1.10"));
    var centValuedFloor =
        policy.calculatePrice(new BigDecimal("0.25"), List.of(), new BigDecimal("0.27"));

    // assert
    assertThat(defaultFloor).isEqualByComparingTo("0.25");
    assertThat(lowerFloor).isEqualByComparingTo("0.20");
    assertThat(zeroFloor).isEqualByComparingTo("0.20");
    assertThat(raisedFloor).isEqualByComparingTo("1.10");
    assertThat(centValuedFloor).isEqualByComparingTo("0.27");
  }

  @Test
  void calculatePriceShouldApplyFloorsBelowTheIntakeThreshold() {
    // arrange
    var marketPrice = new BigDecimal("0.10");

    // act
    var defaultFloor =
        policy.calculatePrice(marketPrice, List.of(), GetPriceService.DEFAULT_PRICE_FLOOR);
    var lowerFloor = policy.calculatePrice(marketPrice, List.of(), new BigDecimal("0.05"));
    var zeroFloor = policy.calculatePrice(marketPrice, List.of(), BigDecimal.ZERO);
    var centValuedFloor = policy.calculatePrice(marketPrice, List.of(), new BigDecimal("0.13"));

    // assert
    assertThat(defaultFloor).isEqualByComparingTo("0.25");
    assertThat(lowerFloor).isEqualByComparingTo("0.10");
    assertThat(zeroFloor).isEqualByComparingTo("0.10");
    assertThat(centValuedFloor).isEqualByComparingTo("0.13");
  }

  @Test
  void calculatePriceShouldComputeCompetitorTicks() {
    // arrange
    var smallCompetitor =
        List.of(new CompetitorPricing.CompetitorPriceTier(new BigDecimal("1.00"), Set.of("alpha")));
    var largeCompetitor =
        List.of(
            new CompetitorPricing.CompetitorPriceTier(new BigDecimal("10.00"), Set.of("alpha")));

    // act
    var smallPrice =
        policy.calculatePrice(
            new BigDecimal("1.00"), smallCompetitor, GetPriceService.DEFAULT_PRICE_FLOOR);
    var largePrice =
        policy.calculatePrice(
            new BigDecimal("10.00"), largeCompetitor, GetPriceService.DEFAULT_PRICE_FLOOR);

    // assert
    assertThat(smallPrice).isEqualByComparingTo("0.95");
    assertThat(largePrice).isEqualByComparingTo("9.75");
  }

  private void assertPrice(String marketPrice, String expectedPrice) {
    var result =
        policy.calculatePrice(
            new BigDecimal(marketPrice), List.of(), GetPriceService.DEFAULT_PRICE_FLOOR);
    assertThat(result).isEqualByComparingTo(expectedPrice);
  }
}
