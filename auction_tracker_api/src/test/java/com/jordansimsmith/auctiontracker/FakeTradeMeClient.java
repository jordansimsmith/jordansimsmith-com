package com.jordansimsmith.auctiontracker;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
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
    var fullSearchUrl = buildSearchUrl(baseUrl, searchTerm, minPrice, maxPrice);
    return searchResponses.getOrDefault(fullSearchUrl, List.of());
  }

  @Override
  public URI getSearchUrl(SearchFactory.Search search) {
    var urlString =
        buildSearchUrl(search.baseUrl(), search.searchTerm(), search.minPrice(), search.maxPrice());
    return URI.create(urlString);
  }

  public void addSearchResponse(
      URI baseUrl,
      String searchTerm,
      @Nullable Double minPrice,
      @Nullable Double maxPrice,
      List<TradeMeItem> items) {
    var key = buildSearchUrl(baseUrl, searchTerm, minPrice, maxPrice);
    searchResponses.put(key, new ArrayList<>(items));
  }

  public void reset() {
    searchResponses.clear();
  }

  private String buildSearchUrl(
      URI baseUrl, String searchTerm, @Nullable Double minPrice, @Nullable Double maxPrice) {
    var url =
        baseUrl.toString()
            + "?search_string="
            + URLEncoder.encode(searchTerm, StandardCharsets.UTF_8);

    if (minPrice != null) {
      url += "&price_min=" + minPrice.intValue();
    }

    if (maxPrice != null) {
      url += "&price_max=" + maxPrice.intValue();
    }

    url += "&sort_order=expirydesc";

    return url;
  }
}
