package com.jordansimsmith.tcginventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

public class ManaBoxCsvParserTest {
  private static final String HEADER =
      "Name,Set code,Set name,Collector number,Foil,Rarity,Quantity,Scryfall"
          + " ID,Misprint,Altered,Condition,Language";

  @Test
  void parseShouldReturnRowsForValidCsv() {
    // arrange
    var csv =
        HEADER
            + "\n"
            + "Llanowar"
            + " Elves,DOM,Dominaria,168,Normal,Common,1,581b7327-3215-4a4f-b4ae-d9d4002ba882,false,false,near_mint,en";

    // act
    var rows = ManaBoxCsvParser.parse(csv);

    // assert
    assertThat(rows).hasSize(1);
    var row = rows.get(0);
    assertThat(row.name()).isEqualTo("Llanowar Elves");
    assertThat(row.setCode()).isEqualTo("dom");
    assertThat(row.setName()).isEqualTo("Dominaria");
    assertThat(row.collectorNumber()).isEqualTo("168");
    assertThat(row.finish()).isEqualTo("normal");
    assertThat(row.condition()).isEqualTo("NM");
    assertThat(row.scryfallId()).isEqualTo("581b7327-3215-4a4f-b4ae-d9d4002ba882");
    assertThat(row.language()).isEqualTo("en");
    assertThat(row.quantity()).isEqualTo(1);
  }

  @Test
  void parseShouldExpandQuantityIntoMultipleRows() {
    // arrange
    var csv =
        HEADER
            + "\n"
            + "Sol Ring,CMR,Commander Legends,472,Normal,Mythic"
            + " Rare,3,58b26011-e103-45c4-a253-900f4e6b2eeb,false,false,mint,en";

    // act
    var rows = ManaBoxCsvParser.parse(csv);

    // assert
    assertThat(rows).hasSize(1);
    assertThat(rows.get(0).quantity()).isEqualTo(3);
  }

  @Test
  void parseShouldMapConditionValues() {
    // act & assert
    assertThat(parseSingleRowCondition("mint")).isEqualTo("NM");
    assertThat(parseSingleRowCondition("near_mint")).isEqualTo("NM");
    assertThat(parseSingleRowCondition("excellent")).isEqualTo("LP");
    assertThat(parseSingleRowCondition("good")).isEqualTo("MP");
    assertThat(parseSingleRowCondition("light_played")).isEqualTo("HP");
    assertThat(parseSingleRowCondition("played")).isEqualTo("HP");
    assertThat(parseSingleRowCondition("poor")).isEqualTo("DMG");
  }

  private static String parseSingleRowCondition(String condition) {
    var csv =
        HEADER
            + "\n"
            + "Card,SET,Set"
            + " Name,1,Normal,Common,1,581b7327-3215-4a4f-b4ae-d9d4002ba882,false,false,"
            + condition
            + ",en";
    return ManaBoxCsvParser.parse(csv).get(0).condition();
  }

  @Test
  void parseShouldRejectEmptyCondition() {
    // arrange
    var csv =
        HEADER
            + "\n"
            + "Card,SET,Set"
            + " Name,1,Normal,Common,1,581b7327-3215-4a4f-b4ae-d9d4002ba882,false,false,,en";

    // act & assert
    assertThatThrownBy(() -> ManaBoxCsvParser.parse(csv))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Condition");
  }

  @Test
  void parseShouldHandleFoilAndEtchedFinishes() {
    // arrange
    var csv =
        HEADER
            + "\n"
            + "Card,SET,Set"
            + " Name,1,Foil,Rare,1,581b7327-3215-4a4f-b4ae-d9d4002ba882,false,false,near_mint,en\n"
            + "Card,SET,Set"
            + " Name,2,Etched,Rare,1,581b7327-3215-4a4f-b4ae-d9d4002ba882,false,false,near_mint,en";

    // act
    var rows = ManaBoxCsvParser.parse(csv);

    // assert
    assertThat(rows.get(0).finish()).isEqualTo("foil");
    assertThat(rows.get(1).finish()).isEqualTo("etched");
  }

  @Test
  void parseShouldIgnoreMisprintAlteredAndRarityColumns() {
    // arrange
    var csv =
        HEADER
            + "\n"
            + "Card,SET,Set Name,1,Normal,Mythic"
            + " Rare,1,581b7327-3215-4a4f-b4ae-d9d4002ba882,true,true,near_mint,en";

    // act
    var rows = ManaBoxCsvParser.parse(csv);

    // assert
    assertThat(rows).hasSize(1);
  }

  @Test
  void parseShouldRejectEmptyCsv() {
    assertThatThrownBy(() -> ManaBoxCsvParser.parse(""))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("empty");
  }

  @Test
  void parseShouldRejectCsvWithHeaderOnly() {
    assertThatThrownBy(() -> ManaBoxCsvParser.parse(HEADER))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("no cards");
  }

  @Test
  void parseShouldRejectInvalidScryfallId() {
    // arrange
    var csv = HEADER + "\nCard,SET,Set Name,1,Normal,Common,1,not-a-uuid,false,false,near_mint,en";

    // act & assert
    assertThatThrownBy(() -> ManaBoxCsvParser.parse(csv))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Scryfall ID must be a UUID");
  }

  @Test
  void parseShouldRejectInvalidFinish() {
    // arrange
    var csv =
        HEADER
            + "\n"
            + "Card,SET,Set"
            + " Name,1,Unknown,Common,1,581b7327-3215-4a4f-b4ae-d9d4002ba882,false,false,near_mint,en";

    // act & assert
    assertThatThrownBy(() -> ManaBoxCsvParser.parse(csv))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Foil must be normal, foil, or etched");
  }

  @Test
  void parseShouldRejectUnknownCondition() {
    // arrange
    var csv =
        HEADER
            + "\n"
            + "Card,SET,Set"
            + " Name,1,Normal,Common,1,581b7327-3215-4a4f-b4ae-d9d4002ba882,false,false,terrible,en";

    // act & assert
    assertThatThrownBy(() -> ManaBoxCsvParser.parse(csv))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unknown Condition");
  }

  @Test
  void parseShouldHandleBom() {
    // arrange
    var csv =
        "\ufeff"
            + HEADER
            + "\n"
            + "Card,SET,Set"
            + " Name,1,Normal,Common,1,581b7327-3215-4a4f-b4ae-d9d4002ba882,false,false,near_mint,en";

    // act
    var rows = ManaBoxCsvParser.parse(csv);

    // assert
    assertThat(rows).hasSize(1);
  }
}
