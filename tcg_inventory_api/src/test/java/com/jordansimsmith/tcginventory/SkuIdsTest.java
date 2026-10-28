package com.jordansimsmith.tcginventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jordansimsmith.tcginventory.games.client.FakeGamesService;
import com.jordansimsmith.tcginventory.games.client.Finish;
import org.junit.jupiter.api.Test;

public class SkuIdsTest {
  @Test
  void formatShouldBuildCanonicalMagicSkuId() {
    // arrange
    var identity = new CardIdentity("mtg", "29ba5a2d-d787-4214-8cd7-7f2bcea938f8");

    // act
    var skuId =
        SkuIds.format(FakeGamesService.MAGIC_THE_GATHERING, identity, "normal", Condition.NM);

    // assert
    assertThat(skuId).isEqualTo("mtg#scryfall#29ba5a2d-d787-4214-8cd7-7f2bcea938f8#normal#NM");
  }

  @Test
  void formatShouldPreserveAsciiUnreservedExternalId() {
    // arrange
    var identity = new CardIdentity("mtg", "a-._~451396");

    // act
    var skuId = SkuIds.format(FakeGamesService.MAGIC_THE_GATHERING, identity, "foil", Condition.LP);

    // assert
    assertThat(skuId).isEqualTo("mtg#scryfall#a-._~451396#foil#LP");
  }

  @Test
  void formatShouldDeriveCanonicalSourceAcrossEveryFinishAndCondition() {
    // arrange
    var identity = new CardIdentity("mtg", "card-id");

    // act / assert
    for (var finish : FakeGamesService.MAGIC_THE_GATHERING.finishes()) {
      for (var condition : Condition.values()) {
        assertThat(
                SkuIds.format(
                    FakeGamesService.MAGIC_THE_GATHERING, identity, finish.id(), condition))
            .isEqualTo("mtg#scryfall#card-id#" + finish.id() + "#" + condition.name());
      }
    }
  }

  @Test
  void formatShouldUseTcgplayerProductIdAndKeepPokemonFinishesDistinct() {
    // arrange
    var identity = new CardIdentity("pokemon", "283917");

    // act / assert
    assertThat(SkuIds.format(FakeGamesService.POKEMON_ENGLISH, identity, "normal", Condition.NM))
        .isEqualTo("pokemon#tcgplayer#283917#normal#NM");
    assertThat(
            SkuIds.format(
                FakeGamesService.POKEMON_ENGLISH, identity, "reverse_holofoil", Condition.NM))
        .isEqualTo("pokemon#tcgplayer#283917#reverse_holofoil#NM");
    assertThat(SkuIds.format(FakeGamesService.POKEMON_ENGLISH, identity, "holofoil", Condition.NM))
        .isEqualTo("pokemon#tcgplayer#283917#holofoil#NM");
  }

  @Test
  void cardIdentityShouldRejectMalformedTokensAndBlankExternalIds() {
    // arrange / act / assert
    assertThatThrownBy(() -> new CardIdentity("Magic", "id"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new CardIdentity("mtg", "  "))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void cardIdentityShouldRejectExternalIdsOutsideAsciiUnreservedSet() {
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

  @Test
  void formatShouldRejectUnknownGameUnsupportedFinishAndMissingCondition() {
    // arrange
    var unknownGame = new CardIdentity("pokemon_jp", "id");
    var identity = new CardIdentity("mtg", "id");

    // act / assert
    assertThatThrownBy(
            () ->
                SkuIds.format(
                    FakeGamesService.POKEMON_ENGLISH, unknownGame, "normal", Condition.NM))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                SkuIds.format(
                    FakeGamesService.MAGIC_THE_GATHERING,
                    identity,
                    "reverse_holofoil",
                    Condition.NM))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> SkuIds.format(FakeGamesService.MAGIC_THE_GATHERING, identity, "normal", null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void gameRegistryShouldExposeImmutableFinishConfiguration() {
    // arrange / act
    var magic = FakeGamesService.MAGIC_THE_GATHERING;

    // assert
    assertThat(magic).isEqualTo(FakeGamesService.MAGIC_THE_GATHERING);
    assertThat(magic.finishes())
        .containsExactly(
            new Finish("normal", "Normal"),
            new Finish("foil", "Foil"),
            new Finish("etched", "Etched"));
    assertThatThrownBy(
            () -> magic.finishes().add(new Finish("reverse_holofoil", "Reverse Holofoil")))
        .isInstanceOf(UnsupportedOperationException.class);
  }
}
