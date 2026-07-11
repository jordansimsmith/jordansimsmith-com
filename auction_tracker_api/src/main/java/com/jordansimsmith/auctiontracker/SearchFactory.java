package com.jordansimsmith.auctiontracker;

import java.net.URI;
import java.util.List;
import javax.annotation.Nullable;

public interface SearchFactory {
  enum Condition {
    ALL,
    NEW,
    USED
  }

  record Judge(String prompt, String model, String reasoningEffort, List<String> criteria) {}

  record Search(
      URI baseUrl,
      String searchTerm,
      @Nullable Double minPrice,
      @Nullable Double maxPrice,
      Condition condition,
      @Nullable Judge judge) {}

  List<Search> findSearches();
}
