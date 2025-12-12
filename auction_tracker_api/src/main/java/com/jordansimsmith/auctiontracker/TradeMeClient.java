package com.jordansimsmith.auctiontracker;

import java.net.URI;
import java.util.List;
import javax.annotation.Nullable;

public interface TradeMeClient {
  record TradeMeItem(String url, String title, String description) {}

  List<TradeMeItem> searchItems(
      URI baseUrl,
      String searchTerm,
      @Nullable Double minPrice,
      @Nullable Double maxPrice,
      SearchFactory.Condition condition);

  URI getSearchUrl(SearchFactory.Search search);
}
