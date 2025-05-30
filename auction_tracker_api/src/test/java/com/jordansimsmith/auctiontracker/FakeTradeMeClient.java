package com.jordansimsmith.auctiontracker;

import java.net.URI;
import java.util.List;
import javax.annotation.Nullable;

public class FakeTradeMeClient implements TradeMeClient {
  @Override
  public List<TradeMeItem> searchItems(
      URI baseUrl, String searchTerm, @Nullable Double minPrice, @Nullable Double maxPrice) {
    return List.of();
  }
}
