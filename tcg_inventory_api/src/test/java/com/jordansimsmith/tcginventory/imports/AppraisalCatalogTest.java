package com.jordansimsmith.tcginventory.imports;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class AppraisalCatalogTest {
  @Test
  void appraisalCatalogsShouldRejectUnregisteredGames() {
    // arrange
    var game = "unknown_game";

    // act/assert
    assertThatThrownBy(() -> AppraisalCatalogs.get(game))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("no appraisal catalog configured for game: " + game);
  }
}
