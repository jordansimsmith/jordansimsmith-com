package com.jordansimsmith.auctiontracker;

import java.util.ArrayList;
import java.util.List;

public class FakeSearchFactory implements SearchFactory {
  private final List<Search> searches = new ArrayList<>();

  @Override
  public List<Search> findSearches() {
    return List.copyOf(searches);
  }

  @Override
  public Search getSearch(String id) {
    return searches.stream()
        .filter(search -> search.id().equals(id))
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("unknown search: " + id));
  }

  public void addSearches(List<Search> newSearches) {
    searches.addAll(newSearches);
  }

  public void reset() {
    searches.clear();
  }
}
