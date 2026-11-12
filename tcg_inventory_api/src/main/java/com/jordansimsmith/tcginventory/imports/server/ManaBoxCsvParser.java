package com.jordansimsmith.tcginventory.imports.server;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.dataformat.csv.CsvMapper;
import com.fasterxml.jackson.dataformat.csv.CsvSchema;
import com.jordansimsmith.tcginventory.games.client.Game;
import com.jordansimsmith.tcginventory.imports.client.CreateImportRow;
import com.jordansimsmith.tcginventory.imports.client.Language;
import com.jordansimsmith.tcginventory.inventory.client.Condition;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class ManaBoxCsvParser implements ImportCsvParser {
  @JsonIgnoreProperties(ignoreUnknown = true)
  private record ManaBoxRow(
      @JsonProperty("Name") String name,
      @JsonProperty("Set code") String setCode,
      @JsonProperty("Set name") String setName,
      @JsonProperty("Collector number") String collectorNumber,
      @JsonProperty("Foil") String finish,
      @JsonProperty("Quantity") int quantity,
      @JsonProperty("Scryfall ID") String externalId,
      @JsonProperty("Condition") String condition,
      @JsonProperty("Language") String language) {}

  @Override
  public List<CreateImportRow> parse(String csv, Game game) {
    var content = csv.startsWith("\ufeff") ? csv.substring(1) : csv;
    if (content.isBlank()) {
      throw new IllegalArgumentException("CSV is empty");
    }

    var mapper = new CsvMapper();
    var schema = CsvSchema.emptySchema().withHeader();
    List<ManaBoxRow> rawRows;
    try {
      rawRows =
          mapper.readerFor(ManaBoxRow.class).with(schema).<ManaBoxRow>readValues(content).readAll();
    } catch (IOException e) {
      throw new IllegalArgumentException("invalid CSV: " + e.getMessage(), e);
    }

    if (rawRows.isEmpty()) {
      throw new IllegalArgumentException("CSV contains no cards");
    }

    var result = new ArrayList<CreateImportRow>();
    for (int index = 0; index < rawRows.size(); index++) {
      var row = parseRow(rawRows.get(index), index + 2, game);
      for (int copy = 0; copy < rawRows.get(index).quantity(); copy++) {
        result.add(row);
      }
    }
    return result;
  }

  private static CreateImportRow parseRow(ManaBoxRow row, int rowNumber, Game game) {
    requireNonBlank(row.name(), "Name", rowNumber);
    requireNonBlank(row.setCode(), "Set code", rowNumber);
    requireNonBlank(row.setName(), "Set name", rowNumber);
    requireNonBlank(row.collectorNumber(), "Collector number", rowNumber);
    requireNonBlank(row.externalId(), "Scryfall ID", rowNumber);
    requireNonBlank(row.condition(), "Condition", rowNumber);
    requireNonBlank(row.language(), "Language", rowNumber);

    if (row.quantity() <= 0) {
      throw new IllegalArgumentException(
          "row " + rowNumber + ": Quantity must be a positive integer");
    }

    var finish = row.finish() == null ? "" : row.finish().toLowerCase(Locale.ROOT);
    if (game.finishes().stream().noneMatch(candidate -> candidate.id().equals(finish))) {
      throw new IllegalArgumentException("row " + rowNumber + ": Foil must be a supported finish");
    }

    var conditionValue = row.condition().toLowerCase(Locale.ROOT);
    var condition =
        switch (conditionValue) {
          case "mint", "near_mint" -> Condition.NM;
          case "excellent" -> Condition.LP;
          case "good" -> Condition.MP;
          case "light_played", "played" -> Condition.HP;
          case "poor" -> Condition.DMG;
          default ->
              throw new IllegalArgumentException(
                  "row " + rowNumber + ": unknown Condition " + conditionValue);
        };

    var language =
        switch (row.language().toLowerCase(Locale.ROOT)) {
          case "en", "english" -> Language.ENGLISH;
          case "ja", "jp", "japanese" -> Language.JAPANESE;
          default -> Language.OTHER;
        };
    return new CreateImportRow(
        row.externalId(),
        row.name(),
        row.setCode().toLowerCase(Locale.ROOT),
        row.setName(),
        row.collectorNumber(),
        finish,
        condition,
        language);
  }

  private static void requireNonBlank(String value, String column, int rowNumber) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("row " + rowNumber + ": " + column + " must not be empty");
    }
  }
}
