package com.jordansimsmith.tcginventory.imports.server;

public class ImportCsvParserFactory {
  private final ImportCsvParser magicTheGatheringParser;

  public ImportCsvParserFactory(ImportCsvParser magicTheGatheringParser) {
    this.magicTheGatheringParser = magicTheGatheringParser;
  }

  public ImportCsvParser forGame(String gameId) {
    return switch (gameId) {
      case "mtg" -> magicTheGatheringParser;
      default -> throw new IllegalArgumentException("no CSV parser configured for game: " + gameId);
    };
  }
}
