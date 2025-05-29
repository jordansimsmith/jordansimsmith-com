package com.jordansimsmith.auctiontracker;

import java.net.URI;
import java.util.List;
import javax.annotation.Nullable;

public interface SearchFactory {
  record Search(
      URI baseUrl,
      String searchTerm,
      @Nullable Double minPrice,
      @Nullable Double maxPrice,
      @Nullable String evaluationPrompt) {}

  List<Search> findSearches();
}
