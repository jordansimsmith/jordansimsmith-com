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
              75.0,
              Condition.USED),
          new Search(
              URI.create(
                  "https://www.trademe.co.nz/a/marketplace/sports/golf/irons/steel-shaft/search"),
              "ping iron",
              null,
              75.0,
              Condition.USED),
          new Search(
              URI.create(
                  "https://www.trademe.co.nz/a/marketplace/sports/golf/irons/steel-shaft/search"),
              "taylormade iron",
              null,
              75.0,
              Condition.USED),
          new Search(
              URI.create(
                  "https://www.trademe.co.nz/a/marketplace/sports/golf/irons/steel-shaft/search"),
              "callaway iron",
              null,
              75.0,
              Condition.USED),
          new Search(
              URI.create("https://www.trademe.co.nz/a/marketplace/sports/golf/putters/search"),
              "taylormade mallet putter",
              null,
              100.0,
              Condition.USED),
          new Search(
              URI.create("https://www.trademe.co.nz/a/marketplace/sports/golf/putters/search"),
              "odyssey mallet putter",
              null,
              100.0,
              Condition.USED),
          new Search(
              URI.create("https://www.trademe.co.nz/a/marketplace/sports/golf/putters/search"),
              "wilson mallet putter",
              null,
              100.0,
              Condition.USED),
          new Search(
              URI.create(
                  "https://www.trademe.co.nz/a/marketplace/computers/components/cpus/amd/search"),
              "ryzen 7 5700x3d",
              null,
              500.0,
              Condition.USED),
          new Search(
              URI.create(
                  "https://www.trademe.co.nz/a/marketplace/computers/components/cpus/amd/search"),
              "ryzen 7 5800x3d",
              null,
              500.0,
              Condition.USED),
          new Search(
              URI.create(
                  "https://www.trademe.co.nz/a/marketplace/computers/components/memory-ram/16gb-or-more/search"),
              "g.skill trident z 32gb ddr4",
              null,
              200.0,
              Condition.USED),
          new Search(
              URI.create(
                  "https://www.trademe.co.nz/a/marketplace/computers/components/hard-drives/other/search"),
              "nvme 1tb ssd",
              null,
              125.0,
              Condition.USED),
          new Search(
              URI.create(
                  "https://www.trademe.co.nz/a/marketplace/computers/components/hard-drives/other/search"),
              "nvme 2tb ssd",
              null,
              200.0,
              Condition.USED));

  @Override
  public List<Search> findSearches() {
    return SEARCHES;
  }
}
