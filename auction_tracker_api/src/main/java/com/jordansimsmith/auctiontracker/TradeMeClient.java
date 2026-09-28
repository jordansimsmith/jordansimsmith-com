package com.jordansimsmith.auctiontracker;

import java.math.BigDecimal;
import java.net.URI;
import java.util.List;
import java.util.function.Predicate;
import javax.annotation.Nullable;

public interface TradeMeClient {
  record TradeMeItem(
      String url,
      String title,
      String description,
      String sellerUsername,
      BigDecimal startPrice,
      @Nullable BigDecimal buyNowPrice) {}

  List<TradeMeItem> searchItems(
      URI baseUrl,
      String searchTerm,
      @Nullable Double minPrice,
      @Nullable Double maxPrice,
      SearchFactory.Condition condition,
      Predicate<String> shouldFetchItem);

  URI getSearchUrl(SearchFactory.Search search);
}
