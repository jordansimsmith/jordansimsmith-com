package com.jordansimsmith.tcginventory;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

public class InventoryLocationTest {

  @Test
  void formatLocationShouldDeriveBlockAndOffsetFromSequenceNumber() {
    // act & assert
    assertThat(InventoryLocation.formatLocation(0)).isEqualTo("A0-0");
    assertThat(InventoryLocation.formatLocation(50)).isEqualTo("A0-50");
    assertThat(InventoryLocation.formatLocation(4242)).isEqualTo("A42-42");
    assertThat(InventoryLocation.formatLocation(9999)).isEqualTo("A99-99");
    assertThat(InventoryLocation.formatLocation(10000)).isEqualTo("B0-0");
  }

  @Test
  void formatLocationShouldCombineBlockNumberAndOffset() {
    // act & assert
    assertThat(InventoryLocation.formatLocation(0, 47)).isEqualTo("A0-47");
    assertThat(InventoryLocation.formatLocation(42, 0)).isEqualTo("A42-0");
    assertThat(InventoryLocation.formatLocation(100, 5)).isEqualTo("B0-5");
  }
}
