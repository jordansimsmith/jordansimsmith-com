package com.jordansimsmith.tcginventory.catalog;

import com.jordansimsmith.tcginventory.games.client.Game;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class FakeCardCatalogs extends Catalogs {
  private final FakeCardCatalog magicCatalog;
  private final FakeCardCatalog pokemonCatalog;

  public FakeCardCatalogs() {
    this(new FakeCardCatalog(), new FakeCardCatalog());
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

  public void setSearchPage(String game, CatalogPage page) {
    catalog(game).searchPage = page;
  }

  public void setAlternativesPage(String game, CatalogPage page) {
    catalog(game).alternativesPage = page;
  }

  private FakeCardCatalog catalog(String game) {
    return "pokemon".equals(game) ? pokemonCatalog : magicCatalog;
  }

  private static class FakeCardCatalog implements CardCatalog {
    private final Map<String, CatalogCard> cardsById = new LinkedHashMap<>();
    private final List<List<String>> lookupRequests = new ArrayList<>();
    private CatalogException failure;
    private CatalogPage searchPage;
    private CatalogPage alternativesPage;

    @Override
    public CatalogCard getCard(Game ignoredGame, String externalId) {
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
    public Map<String, CatalogCard> findCards(Game ignoredGame, List<String> externalIds) {
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
    public CatalogPage findAlternatives(
        Game ignoredGame, String externalId, String finish, String continuation) {
      if (failure != null) {
        throw failure;
      }
      return Objects.requireNonNull(alternativesPage);
    }

    @Override
    public CatalogPage search(Game ignoredGame, String query, String finish, String continuation) {
      if (failure != null) {
        throw failure;
      }
      return Objects.requireNonNull(searchPage);
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
