package com.jordansimsmith.tcginventory;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.dataformat.csv.CsvMapper;
import com.fasterxml.jackson.dataformat.csv.CsvSchema;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

public class ManaBoxCsvParser {
  private static final Set<String> VALID_FINISHES = Set.of("normal", "foil", "etched");

  private static final Pattern UUID_PATTERN =
      Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");

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
      String scryfallId,
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
    if (!VALID_FINISHES.contains(finish)) {
      throw new IllegalArgumentException(
          "row " + rowNumber + ": Foil must be normal, foil, or etched");
    }

    var scryfallId = raw.scryfallId() == null ? "" : raw.scryfallId().toLowerCase();
    if (!UUID_PATTERN.matcher(scryfallId).matches()) {
      throw new IllegalArgumentException("row " + rowNumber + ": Scryfall ID must be a UUID");
    }

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
        scryfallId,
        raw.language().toLowerCase(),
        raw.quantity());
  }

  private static void requireNonBlank(String value, String column, int rowNumber) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("row " + rowNumber + ": " + column + " must not be empty");
    }
  }
}
