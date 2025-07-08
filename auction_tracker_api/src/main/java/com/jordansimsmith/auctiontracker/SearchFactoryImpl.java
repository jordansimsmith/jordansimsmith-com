package com.jordansimsmith.auctiontracker;

import java.net.URI;
import java.util.List;

public class SearchFactoryImpl implements SearchFactory {
  private static final List<Search> SEARCHES =
      List.of(
          new Search(
              URI.create(
                  "https://www.trademe.co.nz/a/marketplace/sports/golf/irons/steel-shaft/search"),
              "titleist #9 iron",
              null,
              75.0),
          new Search(
              URI.create(
                  "https://www.trademe.co.nz/a/marketplace/sports/golf/irons/steel-shaft/search"),
              "ping #9 iron",
              null,
              75.0),
          new Search(
              URI.create(
                  "https://www.trademe.co.nz/a/marketplace/sports/golf/irons/steel-shaft/search"),
              "taylormade #9 iron",
              null,
              75.0),
          new Search(
              URI.create(
                  "https://www.trademe.co.nz/a/marketplace/sports/golf/irons/steel-shaft/search"),
              "callaway #9 iron",
              null,
              75.0));

  @Override
  public List<Search> findSearches() {
    return SEARCHES;
  }
}
