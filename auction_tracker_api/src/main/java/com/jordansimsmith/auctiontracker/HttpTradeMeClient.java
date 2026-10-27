package com.jordansimsmith.auctiontracker;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HttpTradeMeClient implements TradeMeClient {
  private static final Logger LOGGER = LoggerFactory.getLogger(HttpTradeMeClient.class);
  private static final int PAGE_SIZE = 56;
  private static final int RESERVE_NOT_MET = 2;
  private static final String USER_AGENT =
      "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko)"
          + " Chrome/138.0.0.0 Safari/537.36";

  private final URI apiBaseUri;
  private final ObjectMapper objectMapper;
  private final HttpClient httpClient;
  private final Runnable requestPacer;

  public HttpTradeMeClient(
      URI apiBaseUri, ObjectMapper objectMapper, HttpClient httpClient, Runnable requestPacer) {
    this.apiBaseUri = apiBaseUri;
    this.objectMapper = objectMapper.copy().disable(MapperFeature.ALLOW_COERCION_OF_SCALARS);
    this.httpClient = httpClient;
    this.requestPacer = requestPacer;
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  private record SearchResponse(
      @JsonProperty("List") List<SearchListing> listings,
      @JsonProperty("TotalCount") Long totalCount,
      @JsonProperty("Page") Integer page,
      @JsonProperty("PageSize") Integer pageSize,
      @Nullable @JsonProperty("ErrorDescription") String errorDescription) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  private record SearchListing(
      @JsonProperty("ListingId") Long listingId,
      @JsonProperty("CanonicalPath") String canonicalPath) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  private record ListingResponse(
      @JsonProperty("ListingId") Long listingId,
      @JsonProperty("CanonicalPath") String canonicalPath,
      @JsonProperty("Title") String title,
      @JsonProperty("Body") String body,
      @JsonProperty("Member") Member member,
      @JsonProperty("StartPrice") BigDecimal startPrice,
      @Nullable @JsonProperty("BuyNowPrice") BigDecimal buyNowPrice,
      @Nullable @JsonProperty("ReserveState") Integer reserveState,
      @Nullable @JsonProperty("ErrorDescription") String errorDescription) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  private record Member(@JsonProperty("Nickname") String nickname) {}

  @Override
  public List<TradeMeItem> searchItems(
      URI baseUrl,
      String searchTerm,
      @Nullable Double minPrice,
      @Nullable Double maxPrice,
      SearchFactory.Condition condition,
      Predicate<String> shouldFetchItem) {
    try {
      return doSearchItems(baseUrl, searchTerm, minPrice, maxPrice, condition, shouldFetchItem);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new RuntimeException("Interrupted while searching Trade Me items", e);
    } catch (Exception e) {
      throw new RuntimeException("Failed to search Trade Me items for " + baseUrl, e);
    }
  }

  private List<TradeMeItem> doSearchItems(
      URI baseUrl,
      String searchTerm,
      @Nullable Double minPrice,
      @Nullable Double maxPrice,
      SearchFactory.Condition condition,
      Predicate<String> shouldFetchItem)
      throws IOException, InterruptedException, URISyntaxException {
    var searchUrl = buildSearchUrl(baseUrl, searchTerm, minPrice, maxPrice, condition);
    var categoryPath = baseUrl.getPath().replaceFirst("^/a/", "/");
    if (!categoryPath.startsWith("/marketplace/") || !categoryPath.endsWith("/search")) {
      throw new IllegalArgumentException("Invalid Trade Me search path: " + baseUrl);
    }
    categoryPath = categoryPath.substring(0, categoryPath.length() - "/search".length());
    var apiUrl =
        apiBaseUri.resolve(
            "/v1/search/general.json?"
                + searchUrl.getRawQuery()
                + "&rows="
                + PAGE_SIZE
                + "&page=1&return_canonical=true&canonical_path="
                + URLEncoder.encode(categoryPath, StandardCharsets.UTF_8));
    LOGGER.info("Searching Trade Me API for {}", searchUrl);
    var results = fetchJson(apiUrl, baseUrl.resolve("/"), SearchResponse.class);
    if (results.errorDescription() != null) {
      throw new IOException("Trade Me API returned an error response for " + apiUrl);
    }
    var listings = results.listings();
    var totalCount = results.totalCount();
    var page = results.page();
    var pageSize = results.pageSize();
    if (listings == null
        || totalCount == null
        || totalCount < 0
        || page == null
        || page != 1
        || pageSize == null
        || pageSize < 0
        || pageSize > PAGE_SIZE
        || (totalCount > 0 && pageSize == 0)
        || listings.size() > pageSize
        || listings.size() > totalCount
        || (totalCount > 0 && listings.isEmpty())) {
      throw new IOException("Inconsistent Trade Me search result metadata for " + searchUrl);
    }
    if (pageSize < totalCount) {
      LOGGER.warn(
          "Pagination detected for {}: processing only the first {} of {} results",
          searchUrl,
          pageSize,
          totalCount);
    }

    var urlsById = new LinkedHashMap<String, String>();
    for (var listing : listings) {
      if (listing == null) {
        throw new IOException("Null Trade Me search listing for " + searchUrl);
      }
      var id = listingId(listing.listingId(), apiUrl);
      var url = listingUrl(listing.canonicalPath(), id, baseUrl);
      var previousUrl = urlsById.putIfAbsent(id, url);
      if (previousUrl != null && !previousUrl.equals(url)) {
        throw new IOException("Conflicting canonical paths for Trade Me listing " + id);
      }
    }
    LOGGER.info("Discovered {} listings for {}", urlsById.size(), searchUrl);

    var items = new ArrayList<TradeMeItem>();
    var knownUrlCount = 0;
    var reserveSkippedCount = 0;
    var detailFetchCount = 0;
    for (var entry : urlsById.entrySet()) {
      var id = entry.getKey();
      var url = entry.getValue();
      if (!shouldFetchItem.test(url)) {
        knownUrlCount++;
        continue;
      }
      var detailUrl =
          apiBaseUri.resolve(
              "/v1/listings/" + id + ".json?return_canonical=true&return_member_profile=true");
      detailFetchCount++;
      var detail = fetchJson(detailUrl, baseUrl.resolve("/"), ListingResponse.class);
      if (detail.errorDescription() != null) {
        throw new IOException("Trade Me API returned an error response for " + detailUrl);
      }
      if (!listingId(detail.listingId(), detailUrl).equals(id)
          || !listingUrl(detail.canonicalPath(), id, baseUrl).equals(url)) {
        throw new IOException("Trade Me listing details do not match search result: " + url);
      }
      var reserve = detail.reserveState();
      if (reserve != null && (reserve < 1 || reserve > 3)) {
        throw new IOException("Invalid Trade Me reserve state for " + url);
      }
      if (reserve != null && reserve == RESERVE_NOT_MET) {
        LOGGER.info("Skipping Trade Me listing with unmet reserve: {}", url);
        reserveSkippedCount++;
        continue;
      }

      var title = requiredText(detail.title(), "Title", detailUrl);
      var description =
          requiredText(detail.body(), "Body", detailUrl).replace("\r\n", "\n").replace('\r', '\n');
      if (description.length() > 1000) {
        description = description.substring(0, 1000) + "...";
      }
      if (detail.member() == null) {
        throw new IOException("Missing Trade Me Member for " + detailUrl);
      }
      var sellerUsername = requiredText(detail.member().nickname(), "Nickname", detailUrl);
      var startPrice = requiredPrice(detail.startPrice(), "StartPrice", detailUrl);
      var buyNowPrice =
          detail.buyNowPrice() == null
              ? null
              : requiredPrice(detail.buyNowPrice(), "BuyNowPrice", detailUrl);
      items.add(new TradeMeItem(url, title, description, sellerUsername, startPrice, buyNowPrice));
    }
    LOGGER.info(
        "Completed Trade Me search {}: discovered={}, known_url_skipped={}, details_fetched={},"
            + " parsed={}, reserve_skipped={}",
        searchUrl,
        urlsById.size(),
        knownUrlCount,
        detailFetchCount,
        items.size(),
        reserveSkippedCount);
    return items;
  }

  private <T> T fetchJson(URI url, URI referer, Class<T> responseType)
      throws IOException, InterruptedException {
    var request =
        HttpRequest.newBuilder(url)
            .timeout(Duration.ofSeconds(30))
            .header("Accept", "application/json")
            .header("Referer", referer.toString())
            .header("User-Agent", USER_AGENT)
            .GET()
            .build();
    requestPacer.run();
    var response =
        httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    if (response.statusCode() != 200) {
      throw new IOException("Trade Me API returned HTTP " + response.statusCode() + " for " + url);
    }
    var contentType =
        response
            .headers()
            .firstValue("Content-Type")
            .orElse("")
            .split(";", 2)[0]
            .trim()
            .toLowerCase(Locale.ROOT);
    if (!contentType.equals("application/json")) {
      throw new IOException(
          "Trade Me API returned unexpected content type " + contentType + " for " + url);
    }
    T data =
        objectMapper
            .readerFor(responseType)
            .without(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .readValue(response.body());
    if (data == null) {
      throw new IOException("Trade Me API returned no JSON object for " + url);
    }
    return data;
  }

  private String listingId(@Nullable Long id, URI url) throws IOException {
    if (id == null || id <= 0) {
      throw new IOException("Missing or invalid Trade Me ListingId for " + url);
    }
    return id.toString();
  }

  private String listingUrl(@Nullable String canonicalPathValue, String id, URI websiteUrl)
      throws IOException, URISyntaxException {
    var canonicalPath = URI.create(requiredText(canonicalPathValue, "CanonicalPath", websiteUrl));
    if (canonicalPath.isAbsolute() || canonicalPath.getRawAuthority() != null) {
      throw new IOException("Invalid Trade Me canonical path: " + canonicalPath);
    }
    var path = canonicalPath.getPath();
    if (!path.startsWith("/a/")) {
      path = "/a" + path;
    }
    if (!path.matches("/a/marketplace/.+/listing/" + id)) {
      throw new IOException("Invalid Trade Me canonical path: " + canonicalPath);
    }
    return new URI(websiteUrl.getScheme(), websiteUrl.getAuthority(), path, null, null).toString();
  }

  private String requiredText(@Nullable String value, String field, URI url) throws IOException {
    if (value == null || value.isBlank()) {
      throw new IOException("Missing or invalid Trade Me " + field + " for " + url);
    }
    return value.strip();
  }

  private BigDecimal requiredPrice(@Nullable BigDecimal value, String field, URI url)
      throws IOException {
    if (value == null || value.signum() < 0) {
      throw new IOException("Missing or invalid Trade Me " + field + " for " + url);
    }
    return value;
  }

  @Override
  public URI getSearchUrl(SearchFactory.Search search) {
    return buildSearchUrl(
        search.baseUrl(),
        search.searchTerm(),
        search.minPrice(),
        search.maxPrice(),
        search.condition());
  }

  private URI buildSearchUrl(
      URI baseUrl,
      String searchTerm,
      @Nullable Double minPrice,
      @Nullable Double maxPrice,
      SearchFactory.Condition condition) {
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
    switch (condition) {
      case NEW -> url += "&condition=new";
      case USED -> url += "&condition=used";
      case ALL -> {}
    }
    return URI.create(url + "&sort_order=expirydesc");
  }
}
