package com.jordansimsmith.tcginventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

public class SkuIdsTest {
  @Test
  void formatShouldBuildCanonicalMagicSkuId() {
    // arrange
    var identity = new CardIdentity("mtg", "scryfall", "29ba5a2d-d787-4214-8cd7-7f2bcea938f8");

    // act
    var skuId = SkuIds.format(identity, "normal", Condition.NM);

    // assert
    assertThat(skuId).isEqualTo("mtg#scryfall#29ba5a2d-d787-4214-8cd7-7f2bcea938f8#normal#NM");
  }

  @Test
  void formatShouldPreserveAsciiUnreservedExternalId() {
    // arrange
    var identity = new CardIdentity("mtg", "scryfall", "a-._~451396");

    // act
    var skuId = SkuIds.format(identity, "foil", Condition.LP);

    // assert
    assertThat(skuId).isEqualTo("mtg#scryfall#a-._~451396#foil#LP");
  }

  @Test
  void cardIdentityShouldRejectMalformedTokensAndBlankExternalIds() {
    // arrange / act / assert
    assertThatThrownBy(() -> new CardIdentity("Magic", "scryfall", "id"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new CardIdentity("mtg", "scryfall-source", "id"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new CardIdentity("mtg", "scryfall", "  "))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void cardIdentityShouldRejectExternalIdsOutsideAsciiUnreservedSet() {
    // arrange / act / assert
    assertThatThrownBy(() -> new CardIdentity("mtg", "scryfall", "a#b"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new CardIdentity("mtg", "scryfall", "a%b"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new CardIdentity("mtg", "scryfall", "a b"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new CardIdentity("mtg", "scryfall", "é"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void formatShouldRejectUnknownGameUnsupportedFinishAndMissingCondition() {
    // arrange
    var unknownGame = new CardIdentity("pokemon_jp", "scryfall", "id");
    var identity = new CardIdentity("mtg", "scryfall", "id");

    // act / assert
    assertThatThrownBy(() -> SkuIds.format(unknownGame, "normal", Condition.NM))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> SkuIds.format(identity, "reverse_holofoil", Condition.NM))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> SkuIds.format(identity, "normal", null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void gameRegistryShouldExposeImmutableFinishConfiguration() {
    // arrange / act
    var magic = Games.get("mtg");

    // assert
    assertThat(magic).isEqualTo(Games.MAGIC_THE_GATHERING);
    assertThat(magic.finishes()).containsExactlyInAnyOrder("normal", "foil", "etched");
    assertThatThrownBy(() -> magic.finishes().add("reverse_holofoil"))
        .isInstanceOf(UnsupportedOperationException.class);
  }
}
