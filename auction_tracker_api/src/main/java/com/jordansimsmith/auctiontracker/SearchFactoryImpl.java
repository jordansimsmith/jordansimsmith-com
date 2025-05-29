package com.jordansimsmith.auctiontracker;

import java.net.URI;
import java.util.List;

public class SearchFactoryImpl implements SearchFactory {
  private static final List<Search> SEARCHES =
      List.of(
          new Search(
              URI.create(
                  "https://www.trademe.co.nz/a/marketplace/sports/golf/wedges-chippers/search"),
              "titleist wedge",
              null,
              70.0,
              null),
          new Search(
              URI.create(
                  "https://www.trademe.co.nz/a/marketplace/sports/golf/wedges-chippers/search"),
              "ping wedge",
              null,
              70.0,
              null),
          new Search(
              URI.create(
                  "https://www.trademe.co.nz/a/marketplace/sports/golf/wedges-chippers/search"),
              "taylormade wedge",
              null,
              70.0,
              null),
          new Search(
              URI.create(
                  "https://www.trademe.co.nz/a/marketplace/sports/golf/wedges-chippers/search"),
              "callaway wedge",
              null,
              70.0,
              null));

  @Override
  public List<Search> findSearches() {
    return SEARCHES;
  }
}
