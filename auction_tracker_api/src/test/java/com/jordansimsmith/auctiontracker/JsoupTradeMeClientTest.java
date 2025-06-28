package com.jordansimsmith.auctiontracker;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class JsoupTradeMeClientTest {
  private static final String BASE_URL =
      "https://www.trademe.co.nz/a/marketplace/sports/golf/wedges-chippers/search";
  private static final String SEARCH_HTML =
      """
      <html>
        <body>
          <div class="tm-search-results">
            <a href="/a/marketplace/sports/golf/wedges-chippers/listing/5337003621">Titleist Vokey SM6 Wedge 60*</a>
            <a href="/a/marketplace/sports/golf/wedges-chippers/listing/5337003622">Callaway Mack Daddy 4 Wedge</a>
          </div>
        </body>
      </html>
      """;

  private static final String SEARCH_HTML_WITH_QUERY_PARAMS =
      """
      <html>
        <body>
          <div class="tm-search-results">
            <a href="/a/marketplace/sports/golf/wedges-chippers/listing/5337003623?rsqid=abc123-def456">TaylorMade Wedge 56*</a>
            <a href="/a/marketplace/sports/golf/wedges-chippers/listing/5337003624?rsqid=xyz789-uvw012&ref=search">Ping Glide Wedge</a>
          </div>
        </body>
      </html>
      """;

  private static final String ITEM1_HTML =
      """
      <html>
        <body>
          <h1 class="tm-marketplace-buyer-options__listing_title">Titleist Vokey SM6 Wedge 60* K Grind (Rattle in Head) $1 RESERVE!!!</h1>
          <div class="tm-marketplace-listing-body__container">
            <p>Titleist Vokey SM6 60* K Grind wedge.</p>
            <p>There is a slight rattle in the head when shaken.</p>
            <p>Still plays great and has plenty of grooves left.</p>
          </div>
        </body>
      </html>
      """;

  private static final String ITEM2_HTML =
      """
      <html>
        <body>
          <h1 class="tm-marketplace-koru-listing__title">Callaway Mack Daddy 4 Wedge 52* Steel</h1>
          <div class="tm-marketplace-koru-listing__body">
            <p>Callaway Mack Daddy 4 wedge in 52 degree loft.</p>
            <p>Steel shaft, standard grip.</p>
            <p>Excellent condition with sharp grooves.</p>
          </div>
        </body>
      </html>
      """;

  private static final String ITEM3_HTML =
      """
      <html>
        <body>
          <h1 class="tm-marketplace-buyer-options__listing_title">TaylorMade Wedge 56* Hi-Toe</h1>
          <div class="tm-marketplace-listing-body__container">
            <p>TaylorMade Hi-Toe wedge 56 degree.</p>
            <p>Great condition with minimal wear.</p>
          </div>
        </body>
      </html>
      """;

  private static final String ITEM4_HTML =
      """
      <html>
        <body>
          <h1 class="tm-marketplace-koru-listing__title">Ping Glide Wedge 60* Black Dot</h1>
          <div class="tm-marketplace-koru-listing__body">
            <p>Ping Glide wedge in 60 degree loft.</p>
            <p>Black dot specification for standard lie angle.</p>
            <p>Excellent grooves and performance.</p>
          </div>
        </body>
      </html>
      """;

  private JsoupTradeMeClient client;

  @BeforeEach
  void setUp() {
    client =
        new JsoupTradeMeClient() {
          @Override
          protected Document fetchDocument(String url) {
            return switch (url) {
              case BASE_URL
                  + "?search_string=titleist+wedge&price_max=70&sort_order=expirydesc" -> Jsoup
                  .parse(SEARCH_HTML, BASE_URL);
              case BASE_URL + "?search_string=query+param+test&sort_order=expirydesc" -> Jsoup
                  .parse(SEARCH_HTML_WITH_QUERY_PARAMS, BASE_URL);
              case "https://www.trademe.co.nz/a/marketplace/sports/golf/wedges-chippers/listing/5337003621" -> Jsoup
                  .parse(ITEM1_HTML);
              case "https://www.trademe.co.nz/a/marketplace/sports/golf/wedges-chippers/listing/5337003622" -> Jsoup
                  .parse(ITEM2_HTML);
              case "https://www.trademe.co.nz/a/marketplace/sports/golf/wedges-chippers/listing/5337003623?rsqid=abc123-def456" -> Jsoup
                  .parse(ITEM3_HTML);
              case "https://www.trademe.co.nz/a/marketplace/sports/golf/wedges-chippers/listing/5337003624?rsqid=xyz789-uvw012&ref=search" -> Jsoup
                  .parse(ITEM4_HTML);
              default -> throw new AssertionError("Unexpected URL in test: " + url);
            };
          }
        };
  }

  @Test
  void searchItemsShouldExtractItemsFromSearchResults() {
    // arrange
    var baseUrl = URI.create(BASE_URL);
    var searchTerm = "titleist wedge";
    Double minPrice = null;
    Double maxPrice = 70.0;

    // act
    var items = client.searchItems(baseUrl, searchTerm, minPrice, maxPrice);

    // assert
    assertThat(items).hasSize(2);

    // verify first item
    var item1 =
        items.stream().filter(item -> item.url().contains("5337003621")).findFirst().orElseThrow();
    assertThat(item1.title())
        .isEqualTo("Titleist Vokey SM6 Wedge 60* K Grind (Rattle in Head) $1 RESERVE!!!");
    assertThat(item1.url())
        .isEqualTo(
            "https://www.trademe.co.nz/a/marketplace/sports/golf/wedges-chippers/listing/5337003621");
    assertThat(item1.description()).contains("Titleist Vokey SM6 60* K Grind wedge");
    assertThat(item1.description()).contains("slight rattle in the head");

    // verify second item
    var item2 =
        items.stream().filter(item -> item.url().contains("5337003622")).findFirst().orElseThrow();
    assertThat(item2.title()).isEqualTo("Callaway Mack Daddy 4 Wedge 52* Steel");
    assertThat(item2.url())
        .isEqualTo(
            "https://www.trademe.co.nz/a/marketplace/sports/golf/wedges-chippers/listing/5337003622");
    assertThat(item2.description()).contains("Callaway Mack Daddy 4 wedge");
    assertThat(item2.description()).contains("52 degree loft");
  }

  @Test
  void getSearchUrlShouldReturnFullSearchUrl() {
    // arrange
    var search = new SearchFactory.Search(URI.create(BASE_URL), "titleist wedge", null, 70.0, null);

    // act
    var searchUrl = client.getSearchUrl(search);

    // assert
    assertThat(searchUrl.toString())
        .isEqualTo(BASE_URL + "?search_string=titleist+wedge&price_max=70&sort_order=expirydesc");
  }

  @Test
  void getSearchUrlShouldIncludeMinAndMaxPrice() {
    // arrange
    var search =
        new SearchFactory.Search(URI.create(BASE_URL), "callaway wedge", 50.0, 150.0, null);

    // act
    var searchUrl = client.getSearchUrl(search);

    // assert
    assertThat(searchUrl.toString())
        .isEqualTo(
            BASE_URL
                + "?search_string=callaway+wedge&price_min=50&price_max=150&sort_order=expirydesc");
  }

  @Test
  void searchItemsShouldStripQueryParametersFromItemUrls() {
    // arrange
    var baseUrl = URI.create(BASE_URL);
    var searchTerm = "query param test";
    Double minPrice = null;
    Double maxPrice = null;

    // act
    var items = client.searchItems(baseUrl, searchTerm, minPrice, maxPrice);

    // assert
    assertThat(items).hasSize(2);

    // verify first item has query parameters stripped
    var item1 =
        items.stream().filter(item -> item.url().contains("5337003623")).findFirst().orElseThrow();
    assertThat(item1.title()).isEqualTo("TaylorMade Wedge 56* Hi-Toe");
    assertThat(item1.url())
        .isEqualTo(
            "https://www.trademe.co.nz/a/marketplace/sports/golf/wedges-chippers/listing/5337003623")
        .doesNotContain("rsqid")
        .doesNotContain("?");

    // verify second item has query parameters stripped
    var item2 =
        items.stream().filter(item -> item.url().contains("5337003624")).findFirst().orElseThrow();
    assertThat(item2.title()).isEqualTo("Ping Glide Wedge 60* Black Dot");
    assertThat(item2.url())
        .isEqualTo(
            "https://www.trademe.co.nz/a/marketplace/sports/golf/wedges-chippers/listing/5337003624")
        .doesNotContain("rsqid")
        .doesNotContain("ref")
        .doesNotContain("?");
  }
}
