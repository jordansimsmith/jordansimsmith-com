package com.jordansimsmith.tcginventory.catalog;

import com.jordansimsmith.tcginventory.games.Games.Game;
import java.util.Map;

public class Catalogs {
  private final Map<String, CardCatalog> catalogsBySource;

  public Catalogs(Map<String, CardCatalog> catalogsBySource) {
    this.catalogsBySource = Map.copyOf(catalogsBySource);
  }

  public CardCatalog forGame(Game game) {
    var catalog = catalogsBySource.get(game.externalSource());
    if (catalog == null) {
      throw new CatalogException.BadRequest("no catalog configured for game: " + game.id());
    }
    return catalog;
  }
}
