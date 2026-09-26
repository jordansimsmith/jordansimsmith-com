package com.jordansimsmith.tcginventory.imports;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jordansimsmith.tcginventory.CardIdentity;
import java.util.HashMap;
import org.junit.jupiter.api.Test;

class AppraisalCatalogTest {
  @Test
  void appraisalCatalogsShouldRejectUnregisteredGames() {
    // arrange
    var game = "unknown_game";

    // act/assert
    assertThatThrownBy(() -> AppraisalCatalogs.get(game))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("unsupported game: " + game);
  }

  @Test
  void magicCatalogShouldRejectUnsupportedExternalSources() {
    // arrange
    var catalog = new MagicTheGatheringAppraisalCatalog();
    var identity = new CardIdentity("mtg", "other", "opaque-id");
    var row =
        ImportRowItem.create(
            "jordan",
            "import-1",
            1,
            "Lightning Bolt",
            "lea",
            "Limited Edition Alpha",
            "1",
            "normal",
            "NM",
            "other",
            "opaque-id",
            "en");

    // act/assert
    assertThatThrownBy(() -> catalog.resolve(identity, row, null, new HashMap<>()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("unsupported external source for game mtg: other");
  }
}
