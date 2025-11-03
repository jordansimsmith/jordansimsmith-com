package com.jordansimsmith.auctiontracker;

import java.net.URI;
import java.util.List;

public class SearchFactoryImpl implements SearchFactory {
  private static final List<Search> SEARCHES =
      List.of(
          new Search(
              URI.create(
                  "https://www.trademe.co.nz/a/marketplace/sports/golf/irons/steel-shaft/search"),
              "titleist iron",
              null,
              75.0),
          new Search(
              URI.create(
                  "https://www.trademe.co.nz/a/marketplace/sports/golf/irons/steel-shaft/search"),
              "ping iron",
              null,
              75.0),
          new Search(
              URI.create(
                  "https://www.trademe.co.nz/a/marketplace/sports/golf/irons/steel-shaft/search"),
              "taylormade iron",
              null,
              75.0),
          new Search(
              URI.create(
                  "https://www.trademe.co.nz/a/marketplace/sports/golf/irons/steel-shaft/search"),
              "callaway iron",
              null,
              75.0),
          new Search(
              URI.create("https://www.trademe.co.nz/a/marketplace/sports/golf/putters/search"),
              "taylormade mallet putter",
              null,
              75.0),
          new Search(
              URI.create("https://www.trademe.co.nz/a/marketplace/sports/golf/putters/search"),
              "odyssey mallet putter",
              null,
              75.0));

  @Override
  public List<Search> findSearches() {
    return SEARCHES;
  }
}
