package com.jordansimsmith.tcginventory.catalog.server;

import java.util.List;

public record TcgCsvCatalogDefinition(String gameId, int categoryId, List<FinishMapping> finishes) {
  public TcgCsvCatalogDefinition {
    finishes = List.copyOf(finishes);
  }

  public record FinishMapping(String sourceSubtype, String finishId) {}
}
