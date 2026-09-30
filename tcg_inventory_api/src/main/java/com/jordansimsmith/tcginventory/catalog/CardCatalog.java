package com.jordansimsmith.tcginventory.catalog;

import java.util.List;
import java.util.Map;

public interface CardCatalog {
  CatalogCard getCard(String externalId);

  Map<String, CatalogCard> findCards(List<String> externalIds);

  CatalogPage findAlternatives(String externalId, String finish, String continuation);

  CatalogPage search(String query, String finish, String continuation);
}
