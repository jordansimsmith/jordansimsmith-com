package com.jordansimsmith.tcginventory.games;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

public class GamesTest {
  @Test
  void registryShouldExposeRegisteredGamesAndTheirCapabilities() {
    // arrange / act
    var games = Games.all();

    // assert
    assertThat(games).containsExactly(Games.MAGIC_THE_GATHERING, Games.POKEMON_ENGLISH);
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
    assertThat(magic.scanReviewImageRegions())
        .containsExactly(
            new Games.ScanReviewImageRegion("set_code", "Set code", 0, 0.9, 0.25, 0.1),
            new Games.ScanReviewImageRegion("set_symbol", "Set symbol", 0.75, 0.535, 0.25, 0.1));
    assertThat(magic.supportsFinish("normal")).isTrue();
    assertThat(magic.supportsFinish("reverse_holofoil")).isFalse();
    var pokemon = games.get(1);
    assertThat(pokemon.id()).isEqualTo("pokemon");
    assertThat(pokemon.displayName()).isEqualTo("Pokémon (EN)");
    assertThat(pokemon.externalSource()).isEqualTo("tcgplayer");
    assertThat(pokemon.scanningEnabled()).isFalse();
    assertThat(pokemon.csvImportEnabled()).isFalse();
    assertThat(pokemon.finishes())
        .containsExactly(
            new Games.Finish("normal", "Normal"),
            new Games.Finish("holofoil", "Holofoil"),
            new Games.Finish("reverse_holofoil", "Reverse Holofoil"));
    assertThat(pokemon.scanReviewImageRegions())
        .containsExactly(
            new Games.ScanReviewImageRegion("set_and_number", "Set and number", 0, 0.88, 0.5, 0.12),
            new Games.ScanReviewImageRegion(
                "artwork_stamps_left", "Left artwork stamps", 0, 0.32, 0.45, 0.22),
            new Games.ScanReviewImageRegion(
                "artwork_stamps_right", "Right artwork stamps", 0.55, 0.32, 0.45, 0.22));
    assertThat(pokemon.supportsFinish("reverse_holofoil")).isTrue();
  }

  @Test
  void registryShouldBeImmutable() {
    // arrange
    var magic = Games.MAGIC_THE_GATHERING;

    // act / assert
    assertThatThrownBy(() -> Games.all().clear()).isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> magic.finishes().clear())
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> magic.scanReviewImageRegions().clear())
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void getShouldRejectUnregisteredGame() {
    // arrange / act / assert
    assertThatThrownBy(() -> Games.get("digimon"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("unsupported game: digimon");
  }
}
