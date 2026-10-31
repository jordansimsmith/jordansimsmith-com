package com.jordansimsmith.tcginventory.catalog;

import com.jordansimsmith.tcginventory.games.client.Game;
import java.util.List;
import java.util.Map;

public interface CardCatalog {
  CatalogCard getCard(Game game, String externalId);

  Map<String, CatalogCard> findCards(Game game, List<String> externalIds);

  CatalogPage findAlternatives(Game game, String externalId, String finish, String continuation);

  CatalogPage search(Game game, String query, String finish, String continuation);
}
