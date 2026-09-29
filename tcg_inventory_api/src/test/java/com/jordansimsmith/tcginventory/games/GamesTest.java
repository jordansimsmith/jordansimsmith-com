package com.jordansimsmith.tcginventory.games;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

public class GamesTest {
  @Test
  void registryShouldExposeMagicAndItsCapabilities() {
    // arrange / act
    var games = Games.all();

    // assert
    assertThat(games).containsExactly(Games.MAGIC_THE_GATHERING);
    var magic = games.get(0);
    assertThat(magic.id()).isEqualTo("mtg");
    assertThat(magic.displayName()).isEqualTo("Magic: The Gathering");
    assertThat(magic.scanningEnabled()).isTrue();
    assertThat(magic.csvImportEnabled()).isTrue();
    assertThat(magic.finishes())
        .containsExactly(
            new Games.Finish("normal", "Normal"),
            new Games.Finish("foil", "Foil"),
            new Games.Finish("etched", "Etched"));
    assertThat(magic.supportsFinish("normal")).isTrue();
    assertThat(magic.supportsFinish("reverse_holofoil")).isFalse();
  }

  @Test
  void registryShouldBeImmutable() {
    // arrange
    var magic = Games.MAGIC_THE_GATHERING;

    // act / assert
    assertThatThrownBy(() -> Games.all().clear()).isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> magic.finishes().clear())
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void getShouldRejectUnregisteredGame() {
    // arrange / act / assert
    assertThatThrownBy(() -> Games.get("pokemon"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("unsupported game: pokemon");
  }
}
