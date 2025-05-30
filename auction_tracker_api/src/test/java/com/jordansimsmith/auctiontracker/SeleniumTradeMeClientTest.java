package com.jordansimsmith.auctiontracker;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SeleniumTradeMeClientTest {
  private static final Logger LOGGER = LoggerFactory.getLogger(SeleniumTradeMeClientTest.class);

  @Test
  @Disabled
  void searchItemsShouldFetchAndLogPageSource() {
    // arrange
    var client = new SeleniumTradeMeClient();
    var baseUrl =
        URI.create("https://www.trademe.co.nz/a/marketplace/sports/golf/wedges-chippers/search");
    var searchTerm = "titleist wedge";
    Double minPrice = null;
    Double maxPrice = 70.0;

    // act
    var items = client.searchItems(baseUrl, searchTerm, minPrice, maxPrice);

    // assert
    assertThat(items).isNotNull();
    assertThat(items).isNotEmpty();
    for (var item : items) {
      LOGGER.info(item.toString());
    }
  }
}
