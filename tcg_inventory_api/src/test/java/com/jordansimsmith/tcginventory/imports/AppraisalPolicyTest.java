package com.jordansimsmith.tcginventory.imports;

import static org.assertj.core.api.Assertions.assertThat;

import com.jordansimsmith.tcginventory.pricing.Pricing;
import com.jordansimsmith.tcginventory.pricing.PricingPolicy;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class AppraisalPolicyTest {
  private AppraisalPolicy appraisalPolicy;
  private PricingPolicy pricingPolicy;

  @BeforeEach
  void setUp() {
    appraisalPolicy = new AppraisalPolicy();
    pricingPolicy = new PricingPolicy();
  }

  @Test
  void appraiseShouldDiscardBelowThresholdEvenWhenPriceWasCalculated() {
    // arrange
    var marketPrice = new BigDecimal("0.24");
    var suggestedPrice =
        pricingPolicy.calculatePrice(marketPrice, List.of(), Pricing.DEFAULT_PRICE_FLOOR);

    // act
    var result = appraisalPolicy.appraise(marketPrice, suggestedPrice);

    // assert
    assertThat(suggestedPrice).isEqualByComparingTo("0.25");
    assertThat(result.decision()).isEqualTo(AppraisalPolicy.Decision.DISCARD);
    assertThat(result.suggestedPrice()).isNull();
  }

  @Test
  void appraiseShouldKeepAtThresholdAndReturnCalculatedPrice() {
    // arrange
    var marketPrice = new BigDecimal("0.25");
    var suggestedPrice =
        pricingPolicy.calculatePrice(marketPrice, List.of(), Pricing.DEFAULT_PRICE_FLOOR);

    // act
    var result = appraisalPolicy.appraise(marketPrice, suggestedPrice);

    // assert
    assertThat(result.decision()).isEqualTo(AppraisalPolicy.Decision.KEEP);
    assertThat(result.suggestedPrice()).isEqualByComparingTo("0.25");
  }
}
