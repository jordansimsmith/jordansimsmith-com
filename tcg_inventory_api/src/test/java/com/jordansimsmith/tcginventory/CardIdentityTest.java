package com.jordansimsmith.tcginventory;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

public class CardIdentityTest {
  @Test
  void constructorShouldRejectMalformedGameAndBlankExternalId() {
    // arrange / act / assert
    assertThatThrownBy(() -> new CardIdentity("Magic", "id"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new CardIdentity("mtg", "  "))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void constructorShouldRejectExternalIdsOutsideAsciiUnreservedSet() {
    // arrange / act / assert
    assertThatThrownBy(() -> new CardIdentity("mtg", "a#b"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new CardIdentity("mtg", "a%b"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new CardIdentity("mtg", "a b"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new CardIdentity("mtg", "é"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
