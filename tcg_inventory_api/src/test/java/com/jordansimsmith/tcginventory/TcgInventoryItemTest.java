package com.jordansimsmith.tcginventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

public class TcgInventoryItemTest {

  @Test
  void formatOrderSkShouldSortNumericIdsAcrossDigitLengths() {
    // arrange
    var older = TcgInventoryItem.formatOrderSk("99999");
    var newer = TcgInventoryItem.formatOrderSk("100000");

    // act
    var comparison = newer.compareTo(older);

    // assert
    assertThat(comparison).isPositive();
    assertThat(newer).isEqualTo("ORDER#00000000000000100000");
  }

  @Test
  void formatOrderSkShouldRejectNonNumericIds() {
    // arrange
    var orderId = "abc";

    // act
    var result = assertThatThrownBy(() -> TcgInventoryItem.formatOrderSk(orderId));

    // assert
    result.isInstanceOf(IllegalArgumentException.class);
  }
}
