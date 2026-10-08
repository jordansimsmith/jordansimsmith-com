package com.jordansimsmith.tcginventory.catalog;

import com.jordansimsmith.tcginventory.games.Games.Game;
import java.util.Map;

public class Catalogs {
  private final Map<String, CardCatalog> catalogsByGame;

  public Catalogs(Map<String, CardCatalog> catalogsByGame) {
    this.catalogsByGame = Map.copyOf(catalogsByGame);
  }

  public CardCatalog forGame(Game game) {
    var catalog = catalogsByGame.get(game.id());
    if (catalog == null) {
      throw new CatalogException.BadRequest("no catalog configured for game: " + game.id());
    }
    return catalog;
  }
}
