package com.jordansimsmith.auctiontracker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class HttpTradeMeClientTest {
  private static final URI API_ORIGIN = URI.create("https://api.trademe.test");
  private static final URI SEARCH_URL =
      URI.create("https://www.trademe.co.nz/a/marketplace/gaming/trading-cards/pokemon/search");
  private static final String LISTING_PATH =
      "/marketplace/gaming/trading-cards/pokemon/listing/123";
  private static final Object OMITTED = new Object();
  private static final List<ReserveCapture> RESERVE_CAPTURES =
      List.of(
          new ReserveCapture(
              "6199177399", "pokémon cards bulk and rears", 2, "Reserve not met", false, true),
          new ReserveCapture(
              "6196982554",
              "Pokémon TCG Bulk Lot– 1,600 Yellow Border Cards – Common & Reverse Holo",
              1,
              "Reserve met",
              false,
              false),
          new ReserveCapture(
              "6196637202",
              "70 Pokémon 30th Anniversary card bulk (no duplicates)",
              3,
              "",
              true,
              false),
          new ReserveCapture(
              "6195364593", "Pokemon shiny trainer bulk", null, "No reserve", false, false));

  private final ObjectMapper mapper = new ObjectMapper();
  private final ArrayDeque<StubResponse> responses = new ArrayDeque<>();
  private final List<HttpRequest> requests = new ArrayList<>();
  private HttpClient httpClient;
  private HttpTradeMeClient client;

  @BeforeEach
  void setUp() throws IOException, InterruptedException {
    httpClient = mock(HttpClient.class);
    when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
        .thenAnswer(
            invocation -> {
              requests.add(invocation.getArgument(0));
              return toHttpResponse(responses.removeFirst());
            });
    client = new HttpTradeMeClient(API_ORIGIN, mapper, httpClient, () -> {});
  }

  @Test
  void mapsListingAndBuildsTheExpectedApiRequest() throws Exception {
    queueSearch(100, 56, new ListingRef(123, LISTING_PATH));
    queueJson(detailResponse(123, LISTING_PATH, 1));

    var item = find().getFirst();

    assertThat(item.url())
        .isEqualTo(
            "https://www.trademe.co.nz/a/marketplace/gaming/trading-cards/pokemon/listing/123");
    assertThat(item.title()).isEqualTo("Pokémon collection");
    assertThat(item.description())
        .isEqualTo("Seller text\n\n500 cards < 1000 cards\nLast paragraph.");
    assertThat(item.sellerUsername()).isEqualTo("seller-one");
    assertThat(item.startPrice()).isEqualByComparingTo(BigDecimal.ONE);
    assertThat(item.buyNowPrice()).isEqualByComparingTo(new BigDecimal("75"));

    var searchRequest = requests.getFirst();
    assertThat(searchRequest.uri().getPath()).isEqualTo("/v1/search/general.json");
    assertThat(searchRequest.uri().getRawQuery())
        .contains(
            "search_string=bulk",
            "price_max=200",
            "condition=used",
            "sort_order=expirydesc",
            "rows=56",
            "page=1",
            "return_canonical=true",
            "canonical_path=%2Fmarketplace%2Fgaming%2Ftrading-cards%2Fpokemon");
    assertThat(searchRequest.headers().firstValue("Accept")).contains("application/json");
    assertThat(searchRequest.headers().firstValue("Referer"))
        .contains("https://www.trademe.co.nz/");
    assertThat(searchRequest.headers().firstValue("User-Agent").orElseThrow())
        .doesNotContain("Googlebot");
    assertThat(client.getSearchUrl(search("$1 reserve")).toString())
        .isEqualTo(
            SEARCH_URL
                + "?search_string=%241+reserve&price_min=1&price_max=200&condition=used&sort_order=expirydesc");
    assertThat(requests).hasSize(2);
  }

  @Test
  void acceptsAValidEmptySearch() throws Exception {
    queueSearch(0, 0);

    assertThat(find()).isEmpty();
    assertThat(requests).hasSize(1);
  }

  @Test
  void knownUrlsAreSkippedBeforeDetailRequests() throws Exception {
    queueSearch(1, 56, new ListingRef(123, LISTING_PATH));

    var items =
        client.searchItems(
            SEARCH_URL, "bulk", null, 200.0, SearchFactory.Condition.USED, url -> false);

    assertThat(items).isEmpty();
    assertThat(requests).hasSize(1);
  }

  @Test
  void preservesLocalhostOriginAndNormalizesCanonicalPath() throws Exception {
    var canonicalPath = "/a" + LISTING_PATH + "?rsqid=abc#fragment";
    queueSearch(1, 56, new ListingRef(123, canonicalPath));
    queueJson(detailResponse(123, canonicalPath, 1));
    var localClient = new HttpTradeMeClient(API_ORIGIN, mapper, httpClient, () -> {});
    var localSearchUrl =
        URI.create("http://localhost:8080/a/marketplace/gaming/trading-cards/pokemon/search");

    var items =
        localClient.searchItems(
            localSearchUrl, "bulk", null, 200.0, SearchFactory.Condition.USED, url -> true);

    assertThat(items.getFirst().url())
        .isEqualTo("http://localhost:8080/a/marketplace/gaming/trading-cards/pokemon/listing/123");
    assertThat(requests.getFirst().headers().firstValue("Referer"))
        .contains("http://localhost:8080/");
  }

  @Test
  void failsOnShellsApiErrorsAndInconsistentMetadata() throws Exception {
    queueRaw(200, "text/html; charset=utf-8", "<html>Enable JavaScript</html>");
    assertFailureContains("unexpected content type");

    resetResponses();
    queueRaw(200, "application/json", "{not-json");
    assertFailureContains("Failed to search Trade Me items");

    resetResponses();
    queueRaw(401, "application/json", "{}");
    assertFailureContains("HTTP 401");

    resetResponses();
    queueJson(Map.of("ErrorDescription", "credentials required"));
    assertFailureContains("API returned an error response");

    resetResponses();
    queueSearch(1, 56);
    assertFailureContains("Inconsistent Trade Me search result metadata");
  }

  @Test
  void inlinedReserveCapturesMatchFilteringAndRejectMalformedDetails() throws Exception {
    for (var capture : RESERVE_CAPTURES) {
      resetResponses();
      var id = Long.parseLong(capture.listingId());
      var path = LISTING_PATH.replace("123", capture.listingId());
      queueSearch(1, 56, new ListingRef(id, path));
      var detail =
          detailResponse(
              id, path, capture.reserveState() == null ? OMITTED : capture.reserveState());
      detail.put("Title", capture.title());
      if (capture.buyNowPresent()) {
        detail.put("BuyNowPrice", 20);
      } else {
        detail.remove("BuyNowPrice");
      }
      queueJson(detail);

      assertThat(find().isEmpty()).isEqualTo(capture.shouldSkip());
      assertThat(capture.shouldSkip()).isEqualTo(capture.reserveLabel().equals("Reserve not met"));
    }

    for (var invalidState : new Integer[] {0, 4}) {
      resetResponses();
      queueSearch(1, 56, new ListingRef(123, LISTING_PATH));
      queueJson(detailResponse(123, LISTING_PATH, invalidState));
      assertThatThrownBy(this::find).hasMessageContaining("Failed to search Trade Me items");
    }

    resetResponses();
    queueSearch(1, 56, new ListingRef(123, LISTING_PATH));
    queueJson(detailResponse(123, LISTING_PATH, null));
    assertThat(find()).hasSize(1);

    resetResponses();
    queueSearch(1, 56, new ListingRef(123, LISTING_PATH));
    var missingBody = detailResponse(123, LISTING_PATH, 1);
    missingBody.remove("Body");
    queueJson(missingBody);
    assertThatThrownBy(this::find).hasMessageContaining("Failed to search Trade Me items");
  }

  private List<TradeMeClient.TradeMeItem> find() {
    return client.searchItems(
        SEARCH_URL, "bulk", null, 200.0, SearchFactory.Condition.USED, url -> true);
  }

  private SearchFactory.Search search(String term) {
    return new SearchFactory.Search(
        "pokemon-bulk", SEARCH_URL, term, 1.0, 200.0, SearchFactory.Condition.USED, null);
  }

  private void queueSearch(long totalCount, int pageSize, ListingRef... listings)
      throws IOException {
    var listingMaps =
        Arrays.stream(listings)
            .map(listing -> Map.of("ListingId", listing.id(), "CanonicalPath", listing.path()))
            .toList();
    queueJson(
        Map.of(
            "List", listingMaps,
            "TotalCount", totalCount,
            "Page", 1,
            "PageSize", pageSize,
            "UnknownSearchProperty", true));
  }

  private Map<String, Object> detailResponse(long id, String canonicalPath, Object reserveState) {
    var detail = new LinkedHashMap<String, Object>();
    detail.put("ListingId", id);
    detail.put("CanonicalPath", canonicalPath);
    detail.put("Title", " Pokémon collection ");
    detail.put("Body", " Seller text\r\n\r\n500 cards < 1000 cards\rLast paragraph. ");
    detail.put("Member", Map.of("Nickname", " seller-one ", "UnknownMemberProperty", 42));
    detail.put("StartPrice", 1);
    detail.put("BuyNowPrice", 75);
    detail.put("MaxBidAmount", 51);
    detail.put("UnknownDetailProperty", "ignored");
    if (reserveState != OMITTED) {
      detail.put("ReserveState", reserveState);
    }
    return detail;
  }

  private void queueJson(Object body) throws IOException {
    responses.addLast(
        new StubResponse(200, "application/json; charset=utf-8", mapper.writeValueAsString(body)));
  }

  private void queueRaw(int status, String contentType, String body) {
    responses.addLast(new StubResponse(status, contentType, body));
  }

  private void assertFailureContains(String expectedMessage) {
    assertThatThrownBy(this::find)
        .satisfies(
            error -> {
              var failure = error;
              while (failure.getCause() != null
                  && !String.valueOf(failure.getMessage()).contains(expectedMessage)) {
                failure = failure.getCause();
              }
              assertThat(failure).hasMessageContaining(expectedMessage);
            });
  }

  @SuppressWarnings("unchecked")
  private HttpResponse<String> toHttpResponse(StubResponse stub) {
    var response = (HttpResponse<String>) mock(HttpResponse.class);
    when(response.statusCode()).thenReturn(stub.status());
    when(response.headers())
        .thenReturn(
            HttpHeaders.of(
                Map.of("Content-Type", List.of(stub.contentType())), (name, value) -> true));
    when(response.body()).thenReturn(stub.body());
    return response;
  }

  private void resetResponses() {
    responses.clear();
    requests.clear();
  }

  private record ListingRef(long id, String path) {}

  private record StubResponse(int status, String contentType, String body) {}

  private record ReserveCapture(
      String listingId,
      String title,
      Integer reserveState,
      String reserveLabel,
      boolean buyNowPresent,
      boolean shouldSkip) {}
}
