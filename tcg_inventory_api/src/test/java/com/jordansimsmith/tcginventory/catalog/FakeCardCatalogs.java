package com.jordansimsmith.tcginventory.catalog;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class FakeCardCatalogs extends Catalogs {
  private final FakeCardCatalog scryfallCatalog;

  public FakeCardCatalogs() {
    this(new FakeCardCatalog());
  }

  private FakeCardCatalogs(FakeCardCatalog scryfallCatalog) {
    super(Map.of("scryfall", scryfallCatalog));
    this.scryfallCatalog = scryfallCatalog;
  }

  public void addCard(CatalogCard card) {
    scryfallCatalog.addCard(card);
  }

  public List<List<String>> lookupRequests() {
    return scryfallCatalog.lookupRequests();
  }

  public void setFailure(CatalogException failure) {
    scryfallCatalog.setFailure(failure);
  }

  private static class FakeCardCatalog implements CardCatalog {
    private final Map<String, CatalogCard> cardsById = new LinkedHashMap<>();
    private final List<List<String>> lookupRequests = new ArrayList<>();
    private CatalogException failure;

    @Override
    public CatalogCard.ImageUrls getImageUrls(String externalId) {
      return new CatalogCard.ImageUrls(
          "https://img.example/cards/" + externalId + "/small.jpg",
          "https://img.example/cards/" + externalId + "/normal.jpg");
    }

    @Override
    public CatalogCard getCard(String externalId) {
      throw new UnsupportedOperationException();
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

    void addCard(CatalogCard card) {
      cardsById.put(card.externalId(), card);
    }

    List<List<String>> lookupRequests() {
      return lookupRequests.stream().map(List::copyOf).toList();
    }

    void setFailure(CatalogException failure) {
      this.failure = failure;
    }
  }
}
