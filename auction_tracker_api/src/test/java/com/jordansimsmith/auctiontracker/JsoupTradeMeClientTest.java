package com.jordansimsmith.auctiontracker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
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
          <script id="frend-state" type="application/json">
            {"NGRX_STATE":{"listing":{"cachedDetails":{"entities":{"5337003621":{"item":{"startPrice":1,"buyNowPrice":70,"maxBidAmount":51,"member":{"nickname":" seller-one "}}}}}}}}
          </script>
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
          <script id="frend-state" type="application/json">
            {"NGRX_STATE":{"listing":{"cachedDetails":{"entities":{"5337003622":{"item":{"startPrice":0,"buyNowPrice":65,"member":{"nickname":"seller-two"}}}}}}}}
          </script>
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
          <script id="frend-state" type="application/json">
            {"NGRX_STATE":{"listing":{"cachedDetails":{"entities":{"5337003623":{"item":{"startPrice":1,"buyNowPrice":80,"member":{"nickname":"seller-three"}}}}}}}}
          </script>
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
          <script id="frend-state" type="application/json">
            {"NGRX_STATE":{"listing":{"cachedDetails":{"entities":{"5337003624":{"item":{"startPrice":0,"buyNowPrice":90,"member":{"nickname":"seller-four"}}}}}}}}
          </script>
        </body>
      </html>
      """;

  private static final String SEARCH_HTML_WITH_RESERVE_NOT_MET =
      """
      <html>
        <body>
          <div class="tm-search-results">
            <a href="/a/marketplace/sports/golf/wedges-chippers/listing/5337003625">Golf Wedge with Reserve</a>
            <a href="/a/marketplace/sports/golf/wedges-chippers/listing/5337003626">Regular Golf Wedge</a>
          </div>
        </body>
      </html>
      """;

  private static final String ITEM_WITH_RESERVE_NOT_MET_HTML =
      """
      <html>
        <body>
          <h1 class="tm-marketplace-koru-listing__title">Golf Wedge with Unmet Reserve</h1>
          <div class="tm-marketplace-koru-listing__body">
            <p>Nice golf wedge in good condition.</p>
            <p>Starting bid is lower than reserve price.</p>
          </div>
          <div class="tm-marketplace-koru-listing__commerce-box">
            <div class="tm-koru-commerce-box__container">
              <div class="tm-koru-auction">
                <p class="tm-koru-auction__reserve-state"> Reserve not met </p>
              </div>
            </div>
          </div>
        </body>
      </html>
      """;

  private static final String REGULAR_ITEM_HTML =
      """
      <html>
        <body>
          <h1 class="tm-marketplace-koru-listing__title">Regular Golf Wedge</h1>
          <div class="tm-marketplace-koru-listing__body">
            <p>Regular golf wedge without reserve issues.</p>
            <p>Available for purchase now.</p>
          </div>
          <script id="frend-state" type="application/json">
            {"NGRX_STATE":{"listing":{"cachedDetails":{"entities":{"5337003626":{"item":{"startPrice":0,"buyNowPrice":75,"member":{"nickname":"regular-seller"}}}}}}}}
          </script>
        </body>
      </html>
      """;

  private static final String SEARCH_HTML_WITH_MISSING_PRICE =
      """
      <html>
        <body>
          <div class="tm-search-results">
            <a href="/a/marketplace/sports/golf/wedges-chippers/listing/5337003627">Missing price</a>
          </div>
        </body>
      </html>
      """;

  private static final String ITEM_WITH_MISSING_PRICE_HTML =
      """
      <html>
        <body>
          <h1 class="tm-marketplace-koru-listing__title">Golf Wedge without Price</h1>
          <div class="tm-marketplace-koru-listing__body">
            <p>Listing included to verify required price handling.</p>
          </div>
          <script id="frend-state" type="application/json">
            {"NGRX_STATE":{"listing":{"cachedDetails":{"entities":{"5337003627":{"item":{"maxBidAmount":50,"member":{"nickname":"seller"}}}}}}}}
          </script>
        </body>
      </html>
      """;

  private static final String SEARCH_HTML_WITH_MALFORMED_PRICE =
      """
      <html>
        <body>
          <div class="tm-search-results">
            <a href="/a/marketplace/sports/golf/wedges-chippers/listing/5337003628">Malformed price</a>
          </div>
        </body>
      </html>
      """;

  private static final String ITEM_WITH_MALFORMED_PRICE_HTML =
      """
      <html>
        <body>
          <h1 class="tm-marketplace-koru-listing__title">Golf Wedge with Malformed Price</h1>
          <div class="tm-marketplace-koru-listing__body">
            <p>Listing included to verify malformed price handling.</p>
          </div>
          <script id="frend-state" type="application/json">
            {"NGRX_STATE":{"listing":{"cachedDetails":{"entities":{"5337003628":{"item":{"startPrice":"one dollar","member":{"nickname":"seller"}}}}}}}}
          </script>
        </body>
      </html>
      """;

  private static final String SEARCH_HTML_WITH_MISSING_SELLER_USERNAME =
      """
      <html>
        <body>
          <div class="tm-search-results">
            <a href="/a/marketplace/sports/golf/wedges-chippers/listing/5337003629">Missing seller username</a>
          </div>
        </body>
      </html>
      """;

  private static final String ITEM_WITH_MISSING_SELLER_USERNAME_HTML =
      """
      <html>
        <body>
          <h1 class="tm-marketplace-koru-listing__title">Golf Wedge without Seller Username</h1>
          <div class="tm-marketplace-koru-listing__body">
            <p>Listing included to verify required seller username handling.</p>
          </div>
          <script id="frend-state" type="application/json">
            {"NGRX_STATE":{"listing":{"cachedDetails":{"entities":{"5337003629":{"item":{"startPrice":0,"buyNowPrice":75}}}}}}}
          </script>
        </body>
      </html>
      """;

  private JsoupTradeMeClient client;

  @BeforeEach
  void setUp() {
    client =
        new JsoupTradeMeClient(new ObjectMapper()) {
          @Override
          protected Document fetchDocument(String url) {
            return switch (url) {
              case BASE_URL + "?search_string=titleist+wedge&price_max=70&sort_order=expirydesc" ->
                  Jsoup.parse(SEARCH_HTML, BASE_URL);
              case BASE_URL + "?search_string=query+param+test&sort_order=expirydesc" ->
                  Jsoup.parse(SEARCH_HTML_WITH_QUERY_PARAMS, BASE_URL);
              case BASE_URL + "?search_string=reserve+test&sort_order=expirydesc" ->
                  Jsoup.parse(SEARCH_HTML_WITH_RESERVE_NOT_MET, BASE_URL);
              case BASE_URL + "?search_string=missing+price&sort_order=expirydesc" ->
                  Jsoup.parse(SEARCH_HTML_WITH_MISSING_PRICE, BASE_URL);
              case BASE_URL + "?search_string=malformed+price&sort_order=expirydesc" ->
                  Jsoup.parse(SEARCH_HTML_WITH_MALFORMED_PRICE, BASE_URL);
              case BASE_URL + "?search_string=missing+seller&sort_order=expirydesc" ->
                  Jsoup.parse(SEARCH_HTML_WITH_MISSING_SELLER_USERNAME, BASE_URL);
              case "https://www.trademe.co.nz/a/marketplace/sports/golf/wedges-chippers/listing/5337003621" ->
                  Jsoup.parse(ITEM1_HTML);
              case "https://www.trademe.co.nz/a/marketplace/sports/golf/wedges-chippers/listing/5337003622" ->
                  Jsoup.parse(ITEM2_HTML);
              case "https://www.trademe.co.nz/a/marketplace/sports/golf/wedges-chippers/listing/5337003623?rsqid=abc123-def456" ->
                  Jsoup.parse(ITEM3_HTML);
              case "https://www.trademe.co.nz/a/marketplace/sports/golf/wedges-chippers/listing/5337003624?rsqid=xyz789-uvw012&ref=search" ->
                  Jsoup.parse(ITEM4_HTML);
              case "https://www.trademe.co.nz/a/marketplace/sports/golf/wedges-chippers/listing/5337003625" ->
                  Jsoup.parse(ITEM_WITH_RESERVE_NOT_MET_HTML);
              case "https://www.trademe.co.nz/a/marketplace/sports/golf/wedges-chippers/listing/5337003626" ->
                  Jsoup.parse(REGULAR_ITEM_HTML);
              case "https://www.trademe.co.nz/a/marketplace/sports/golf/wedges-chippers/listing/5337003627" ->
                  Jsoup.parse(ITEM_WITH_MISSING_PRICE_HTML);
              case "https://www.trademe.co.nz/a/marketplace/sports/golf/wedges-chippers/listing/5337003628" ->
                  Jsoup.parse(ITEM_WITH_MALFORMED_PRICE_HTML);
              case "https://www.trademe.co.nz/a/marketplace/sports/golf/wedges-chippers/listing/5337003629" ->
                  Jsoup.parse(ITEM_WITH_MISSING_SELLER_USERNAME_HTML);
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
    var items =
        client.searchItems(baseUrl, searchTerm, minPrice, maxPrice, SearchFactory.Condition.ALL);

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
    assertThat(item1.sellerUsername()).isEqualTo("seller-one");
    assertThat(item1.startPrice()).isEqualByComparingTo(BigDecimal.ONE);
    assertThat(item1.buyNowPrice()).isEqualByComparingTo(new BigDecimal("70"));

    // verify second item
    var item2 =
        items.stream().filter(item -> item.url().contains("5337003622")).findFirst().orElseThrow();
    assertThat(item2.title()).isEqualTo("Callaway Mack Daddy 4 Wedge 52* Steel");
    assertThat(item2.url())
        .isEqualTo(
            "https://www.trademe.co.nz/a/marketplace/sports/golf/wedges-chippers/listing/5337003622");
    assertThat(item2.description()).contains("Callaway Mack Daddy 4 wedge");
    assertThat(item2.description()).contains("52 degree loft");
    assertThat(item2.sellerUsername()).isEqualTo("seller-two");
    assertThat(item2.startPrice()).isEqualByComparingTo(BigDecimal.ZERO);
    assertThat(item2.buyNowPrice()).isEqualByComparingTo(new BigDecimal("65"));
  }

  @Test
  void getSearchUrlShouldReturnFullSearchUrl() {
    // arrange
    var search =
        new SearchFactory.Search(
            URI.create(BASE_URL), "titleist wedge", null, 70.0, SearchFactory.Condition.ALL, null);

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
        new SearchFactory.Search(
            URI.create(BASE_URL), "callaway wedge", 50.0, 150.0, SearchFactory.Condition.ALL, null);

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
    var items =
        client.searchItems(baseUrl, searchTerm, minPrice, maxPrice, SearchFactory.Condition.ALL);

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

  @Test
  void searchItemsShouldFilterOutListingsWithUnmetReserves() {
    // arrange
    var baseUrl = URI.create(BASE_URL);
    var searchTerm = "reserve test";
    Double minPrice = null;
    Double maxPrice = null;

    // act
    var items =
        client.searchItems(baseUrl, searchTerm, minPrice, maxPrice, SearchFactory.Condition.ALL);

    // assert
    assertThat(items).hasSize(1);

    // verify only the item without reserve issues is returned
    var item = items.get(0);
    assertThat(item.title()).isEqualTo("Regular Golf Wedge");
    assertThat(item.url())
        .isEqualTo(
            "https://www.trademe.co.nz/a/marketplace/sports/golf/wedges-chippers/listing/5337003626");
    assertThat(item.description()).contains("Regular golf wedge without reserve issues");
    assertThat(item.sellerUsername()).isEqualTo("regular-seller");

    // verify the item with unmet reserve was filtered out
    var hasUnmetReserveItem = items.stream().anyMatch(i -> i.url().contains("5337003625"));
    assertThat(hasUnmetReserveItem).as("Item with unmet reserve should be filtered out").isFalse();
  }

  @Test
  void searchItemsShouldThrowWhenSellerPriceIsMissing() {
    // arrange
    var baseUrl = URI.create(BASE_URL);

    // act & assert
    assertThatThrownBy(
            () ->
                client.searchItems(
                    baseUrl, "missing price", null, null, SearchFactory.Condition.ALL))
        .isInstanceOf(RuntimeException.class)
        .hasMessage("Failed to search items")
        .hasRootCauseMessage(
            "Could not find valid seller price data on page:"
                + " https://www.trademe.co.nz/a/marketplace/sports/golf/wedges-chippers/listing/5337003627");
  }

  @Test
  void searchItemsShouldThrowWhenSellerPriceIsMalformed() {
    // arrange
    var baseUrl = URI.create(BASE_URL);

    // act & assert
    assertThatThrownBy(
            () ->
                client.searchItems(
                    baseUrl, "malformed price", null, null, SearchFactory.Condition.ALL))
        .isInstanceOf(RuntimeException.class)
        .hasMessage("Failed to search items")
        .hasRootCauseMessage(
            "Could not find valid seller price data on page:"
                + " https://www.trademe.co.nz/a/marketplace/sports/golf/wedges-chippers/listing/5337003628");
  }

  @Test
  void searchItemsShouldThrowWhenSellerUsernameIsMissing() {
    // arrange
    var baseUrl = URI.create(BASE_URL);

    // act & assert
    assertThatThrownBy(
            () ->
                client.searchItems(
                    baseUrl, "missing seller", null, null, SearchFactory.Condition.ALL))
        .isInstanceOf(RuntimeException.class)
        .hasMessage("Failed to search items")
        .hasRootCauseMessage(
            "Could not find valid seller username on page:"
                + " https://www.trademe.co.nz/a/marketplace/sports/golf/wedges-chippers/listing/5337003629");
  }
}
