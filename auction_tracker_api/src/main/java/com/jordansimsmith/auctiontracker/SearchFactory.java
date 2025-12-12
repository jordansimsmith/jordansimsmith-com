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

  record Search(
      URI baseUrl,
      String searchTerm,
      @Nullable Double minPrice,
      @Nullable Double maxPrice,
      Condition condition) {}

  List<Search> findSearches();
}
