package com.jordansimsmith.tcginventory.catalog;

public interface CardCatalog {
  CatalogCard getCard(String externalId);

  CatalogPage findAlternatives(String externalId, String finish, String continuation);

  CatalogPage search(String query, String finish, String continuation);
}
