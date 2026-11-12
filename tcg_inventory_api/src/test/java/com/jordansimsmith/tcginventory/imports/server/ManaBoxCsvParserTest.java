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

  @Test
  void parsesBomAndIgnoresUnsupportedExportColumnsAndLanguages() {
    // arrange
    var csv =
        "\ufeffName,Set code,Set name,Collector number,Foil,Quantity,Scryfall"
            + " ID,Condition,Language,Rarity,Misprint,Altered\n"
            + "Opt,DOM,Dominaria,60,Normal,1,opaque/id,near_mint,korean,Common,true,true";

    // act
    var row = parser.parse(csv, FakeGamesService.MAGIC_THE_GATHERING).getFirst();

    // assert
    assertThat(row.externalId()).isEqualTo("opaque/id");
    assertThat(row.language()).isEqualTo(Language.OTHER);
  }

  @Test
  void rejectsHeaderOnlyCsv() {
    // arrange
    var csv =
        "Name,Set code,Set name,Collector number,Foil,Quantity,Scryfall ID,Condition,Language";

    // act / assert
    assertThatThrownBy(() -> parser.parse(csv, FakeGamesService.MAGIC_THE_GATHERING))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("CSV contains no cards");
  }

  @Test
  void expandsQuantityAndMapsConditionsAndFinishes() {
    // arrange
    var csv =
        header
            + "\nOpt,DOM,Dominaria,60,Normal,2,opaque/id,near_mint,en"
            + "\nSol Ring,CMR,Commander Legends,472,Etched,1,another/id,poor,en";

    // act
    var rows = parser.parse(csv, FakeGamesService.MAGIC_THE_GATHERING);

    // assert
    assertThat(rows)
        .extracting(CreateImportRow::externalId)
        .containsExactly("opaque/id", "opaque/id", "another/id");
    assertThat(rows)
        .extracting(CreateImportRow::condition)
        .containsExactly(Condition.NM, Condition.NM, Condition.DMG);
    assertThat(rows)
        .extracting(CreateImportRow::finish)
        .containsExactly("normal", "normal", "etched");
  }

  @Test
  void mapsManaBoxConditionValues() {
    assertThat(parseCondition("mint")).isEqualTo(Condition.NM);
    assertThat(parseCondition("excellent")).isEqualTo(Condition.LP);
    assertThat(parseCondition("good")).isEqualTo(Condition.MP);
    assertThat(parseCondition("light_played")).isEqualTo(Condition.HP);
    assertThat(parseCondition("played")).isEqualTo(Condition.HP);
    assertThat(parseCondition("poor")).isEqualTo(Condition.DMG);
  }

  @Test
  void rejectsEmptyCsvAndInvalidConditionAndFinish() {
    // arrange
    var emptyCondition = header + "\nOpt,DOM,Dominaria,60,Normal,1,id,,en";
    var unknownCondition = header + "\nOpt,DOM,Dominaria,60,Normal,1,id,bad,en";
    var invalidFinish = header + "\nOpt,DOM,Dominaria,60,Unknown,1,id,near_mint,en";

    // act / assert
    assertThatThrownBy(() -> parser.parse("", FakeGamesService.MAGIC_THE_GATHERING))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("CSV is empty");
    assertThatThrownBy(() -> parser.parse(emptyCondition, FakeGamesService.MAGIC_THE_GATHERING))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Condition must not be empty");
    assertThatThrownBy(() -> parser.parse(unknownCondition, FakeGamesService.MAGIC_THE_GATHERING))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unknown Condition");
    assertThatThrownBy(() -> parser.parse(invalidFinish, FakeGamesService.MAGIC_THE_GATHERING))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Foil must be a supported finish");
  }

  private Condition parseCondition(String condition) {
    var csv =
        "Name,Set code,Set name,Collector number,Foil,Quantity,Scryfall ID,Condition,Language"
            + "\nOpt,DOM,Dominaria,60,Normal,1,id,"
            + condition
            + ",en";
    return parser.parse(csv, FakeGamesService.MAGIC_THE_GATHERING).getFirst().condition();
  }
}
