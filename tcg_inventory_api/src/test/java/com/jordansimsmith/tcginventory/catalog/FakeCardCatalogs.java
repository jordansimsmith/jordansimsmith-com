package com.jordansimsmith.tcginventory.catalog;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class FakeCardCatalogs extends Catalogs {
  private final FakeCardCatalog magicCatalog;
  private final FakeCardCatalog pokemonCatalog;

  public FakeCardCatalogs() {
    this(new FakeCardCatalog("mtg"), new FakeCardCatalog("pokemon"));
  }

  private FakeCardCatalogs(FakeCardCatalog magicCatalog, FakeCardCatalog pokemonCatalog) {
    super(Map.of("mtg", magicCatalog, "pokemon", pokemonCatalog));
    this.magicCatalog = magicCatalog;
    this.pokemonCatalog = pokemonCatalog;
  }

  public void addCard(CatalogCard card) {
    catalog(card.game()).addCard(card);
  }

  public List<List<String>> lookupRequests() {
    return magicCatalog.lookupRequests();
  }

  public void setFailure(CatalogException failure) {
    setFailure("mtg", failure);
  }

  public void setFailure(String game, CatalogException failure) {
    catalog(game).setFailure(failure);
  }

  private FakeCardCatalog catalog(String game) {
    return "pokemon".equals(game) ? pokemonCatalog : magicCatalog;
  }

  private static class FakeCardCatalog implements CardCatalog {
    private final String game;
    private final Map<String, CatalogCard> cardsById = new LinkedHashMap<>();
    private final List<List<String>> lookupRequests = new ArrayList<>();
    private CatalogException failure;

    private FakeCardCatalog(String game) {
      this.game = game;
    }

    @Override
    public CatalogCard.ImageUrls getImageUrls(String externalId) {
      if ("pokemon".equals(game)) {
        return new CatalogCard.ImageUrls(
            "https://tcgplayer-cdn.tcgplayer.com/product/" + externalId + "_200w.jpg",
            "https://tcgplayer-cdn.tcgplayer.com/product/" + externalId + "_in_1000x1000.jpg");
      }
      return new CatalogCard.ImageUrls(
          "https://img.example/cards/" + externalId + "/small.jpg",
          "https://img.example/cards/" + externalId + "/normal.jpg");
    }

    @Override
    public CatalogCard getCard(String externalId) {
      if (failure != null) {
        throw failure;
      }
      var card = cardsById.get(externalId);
      if (card == null) {
        throw new CatalogException.NotFound("card not found");
      }
      return card;
    }

    @Override
    public Map<String, CatalogCard> findCards(List<String> externalIds) {
      lookupRequests.add(List.copyOf(externalIds));
      if (failure != null) {
        throw failure;
      }
      var foundCards = new LinkedHashMap<String, CatalogCard>();
      for (var externalId : externalIds) {
        var card = cardsById.get(externalId);
        if (card != null) {
          foundCards.put(card.externalId(), card);
        }
      }
      return Map.copyOf(foundCards);
    }

    @Override
    public CatalogPage findAlternatives(String externalId, String finish, String continuation) {
      throw new UnsupportedOperationException();
    }

    @Override
    public CatalogPage search(String query, String finish, String continuation) {
      throw new UnsupportedOperationException();
    }

    private void addCard(CatalogCard card) {
      cardsById.put(card.externalId(), card);
    }

    private List<List<String>> lookupRequests() {
      return lookupRequests.stream().map(List::copyOf).toList();
    }

    private void setFailure(CatalogException failure) {
      this.failure = failure;
    }
  }
}
