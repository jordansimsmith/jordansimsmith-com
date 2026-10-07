package com.jordansimsmith.tcginventory.catalog;

import java.util.List;
import java.util.Map;

public class TcgPlayerCatalog implements CardCatalog {
  @Override
  public CatalogCard.ImageUrls getImageUrls(String externalId) {
    if (!externalId.matches("[0-9]+")) {
      throw new CatalogException.BadRequest("TCGplayer external id must be numeric");
    }
    return new CatalogCard.ImageUrls(
        "https://tcgplayer-cdn.tcgplayer.com/product/" + externalId + "_200w.jpg",
        "https://tcgplayer-cdn.tcgplayer.com/product/" + externalId + "_in_1000x1000.jpg");
  }

  @Override
  public CatalogCard getCard(String externalId) {
    throw new CatalogException.BadRequest("catalog review is unavailable for game: pokemon");
  }

  @Override
  public Map<String, CatalogCard> findCards(List<String> externalIds) {
    throw new CatalogException.BadRequest("catalog review is unavailable for game: pokemon");
  }

  @Override
  public CatalogPage findAlternatives(String externalId, String finish, String continuation) {
    throw new CatalogException.BadRequest("catalog review is unavailable for game: pokemon");
  }

  @Override
  public CatalogPage search(String query, String finish, String continuation) {
    throw new CatalogException.BadRequest("catalog review is unavailable for game: pokemon");
  }
}
