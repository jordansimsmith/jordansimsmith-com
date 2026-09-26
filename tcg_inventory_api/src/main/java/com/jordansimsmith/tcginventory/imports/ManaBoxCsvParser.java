package com.jordansimsmith.tcginventory.imports;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.dataformat.csv.CsvMapper;
import com.fasterxml.jackson.dataformat.csv.CsvSchema;
import com.jordansimsmith.tcginventory.CardIdentity;
import com.jordansimsmith.tcginventory.Condition;
import com.jordansimsmith.tcginventory.Games;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class ManaBoxCsvParser {
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record ManaBoxRow(
      @JsonProperty("Name") String name,
      @JsonProperty("Set code") String setCode,
      @JsonProperty("Set name") String setName,
      @JsonProperty("Collector number") String collectorNumber,
      @JsonProperty("Foil") String finish,
      @JsonProperty("Quantity") int quantity,
      @JsonProperty("Scryfall ID") String scryfallId,
      @JsonProperty("Condition") String condition,
      @JsonProperty("Language") String language) {}

  public record ParsedRow(
      String name,
      String setCode,
      String setName,
      String collectorNumber,
      String finish,
      String condition,
      String externalSource,
      String externalId,
      String language,
      int quantity) {}

  public static List<ParsedRow> parse(String csv) {
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

    var result = new ArrayList<ParsedRow>();
    for (int i = 0; i < rawRows.size(); i++) {
      result.add(validate(rawRows.get(i), i + 2));
    }
    return result;
  }

  private static ParsedRow validate(ManaBoxRow raw, int rowNumber) {
    requireNonBlank(raw.name(), "Name", rowNumber);
    requireNonBlank(raw.setCode(), "Set code", rowNumber);
    requireNonBlank(raw.setName(), "Set name", rowNumber);
    requireNonBlank(raw.collectorNumber(), "Collector number", rowNumber);
    requireNonBlank(raw.language(), "Language", rowNumber);

    if (raw.quantity() <= 0) {
      throw new IllegalArgumentException(
          "row " + rowNumber + ": Quantity must be a positive integer");
    }

    var finish = raw.finish() == null ? "" : raw.finish().toLowerCase();
    if (!Games.MAGIC_THE_GATHERING.finishes().contains(finish)) {
      throw new IllegalArgumentException(
          "row " + rowNumber + ": Foil must be normal, foil, or etched");
    }

    var game = Games.MAGIC_THE_GATHERING;
    var identity = new CardIdentity(game.id(), game.externalSource(), raw.scryfallId());

    requireNonBlank(raw.condition(), "Condition", rowNumber);
    var conditionValue = raw.condition().toLowerCase();
    var condition = Condition.fromManaBox(conditionValue);
    if (condition == null) {
      throw new IllegalArgumentException(
          "row " + rowNumber + ": unknown Condition " + conditionValue);
    }

    return new ParsedRow(
        raw.name(),
        raw.setCode().toLowerCase(),
        raw.setName(),
        raw.collectorNumber(),
        finish,
        condition.name(),
        identity.externalSource(),
        identity.externalId(),
        raw.language().toLowerCase(),
        raw.quantity());
  }

  private static void requireNonBlank(String value, String column, int rowNumber) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("row " + rowNumber + ": " + column + " must not be empty");
    }
  }
}
