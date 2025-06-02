package com.jordansimsmith.auctiontracker;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;

public class FakeTradeMeClient implements TradeMeClient {
  private final Map<String, List<TradeMeItem>> searchResponses = new HashMap<>();

  @Override
  public List<TradeMeItem> searchItems(
      URI baseUrl, String searchTerm, @Nullable Double minPrice, @Nullable Double maxPrice) {
    var key = baseUrl + "_" + searchTerm;
    return searchResponses.getOrDefault(key, List.of());
  }

  public void addSearchResponse(URI baseUrl, String searchTerm, List<TradeMeItem> items) {
    var key = baseUrl + "_" + searchTerm;
    searchResponses.put(key, new ArrayList<>(items));
  }

  public void reset() {
    searchResponses.clear();
  }
}
