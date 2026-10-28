package com.jordansimsmith.tcginventory.imports;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class AppraisalPolicyTest {
  private AppraisalPolicy appraisalPolicy;

  @BeforeEach
  void setUp() {
    appraisalPolicy = new AppraisalPolicy();
  }

  @Test
  void appraiseShouldDiscardBelowThresholdEvenWhenPriceWasCalculated() {
    // arrange
    var marketPrice = new BigDecimal("0.24");
    var suggestedPrice = new BigDecimal("0.25");

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
    var suggestedPrice = new BigDecimal("0.30");

    // act
    var result = appraisalPolicy.appraise(marketPrice, suggestedPrice);

    // assert
    assertThat(result.decision()).isEqualTo(AppraisalPolicy.Decision.KEEP);
    assertThat(result.suggestedPrice()).isEqualByComparingTo("0.30");
  }
}
