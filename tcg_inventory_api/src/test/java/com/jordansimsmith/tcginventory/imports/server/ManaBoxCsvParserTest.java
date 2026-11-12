package com.jordansimsmith.tcginventory.imports.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jordansimsmith.tcginventory.games.client.FakeGamesService;
import com.jordansimsmith.tcginventory.imports.client.CreateImportRow;
import com.jordansimsmith.tcginventory.imports.client.Language;
import com.jordansimsmith.tcginventory.inventory.client.Condition;
import org.junit.jupiter.api.Test;

class ManaBoxCsvParserTest {
  private final ManaBoxCsvParser parser = new ManaBoxCsvParser();
  private final String header =
      "Name,Set code,Set name,Collector number,Foil,Quantity,Scryfall ID,Condition,Language";

  @Test
  void parsesManaBoxRowsAndPreservesOpaqueExternalIds() {
    var csv =
        "\ufeff"
            + header
            + "\nOpt,DOM,Dominaria,60,Normal,2,opaque/id,near_mint,en"
            + "\nSol Ring,CMR,Commander Legends,472,Foil,1,another/id,excellent,jp";

    var rows = parser.parse(csv, FakeGamesService.MAGIC_THE_GATHERING);

    assertThat(rows)
        .containsExactly(
            new CreateImportRow(
                "opaque/id",
                "Opt",
                "dom",
                "Dominaria",
                "60",
                "normal",
                Condition.NM,
                Language.ENGLISH),
            new CreateImportRow(
                "opaque/id",
                "Opt",
                "dom",
                "Dominaria",
                "60",
                "normal",
                Condition.NM,
                Language.ENGLISH),
            new CreateImportRow(
                "another/id",
                "Sol Ring",
                "cmr",
                "Commander Legends",
                "472",
                "foil",
                Condition.LP,
                Language.JAPANESE));
  }

  @Test
  void mapsJapaneseLanguageAndRejectsInvalidRows() {
    var csv = header + "\nOpt,DOM,Dominaria,60,Normal,1,id,good,ja";

    assertThat(parser.parse(csv, FakeGamesService.MAGIC_THE_GATHERING).getFirst().language())
        .isEqualTo(Language.JAPANESE);
    assertThat(
            parser
                .parse(
                    header + "\nOpt,DOM,Dominaria,60,Normal,1,id,good,korean",
                    FakeGamesService.MAGIC_THE_GATHERING)
                .getFirst()
                .language())
        .isEqualTo(Language.OTHER);
    assertThatThrownBy(
            () ->
                parser.parse(
                    header + "\nOpt,DOM,Dominaria,60,Normal,0,id,good,ja",
                    FakeGamesService.MAGIC_THE_GATHERING))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Quantity");
  }
}
