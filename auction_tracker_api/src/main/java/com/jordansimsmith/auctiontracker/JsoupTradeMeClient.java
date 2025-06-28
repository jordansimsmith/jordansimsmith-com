package com.jordansimsmith.auctiontracker;

import com.google.common.annotations.VisibleForTesting;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class JsoupTradeMeClient implements TradeMeClient {
  private static final Logger LOGGER = LoggerFactory.getLogger(JsoupTradeMeClient.class);

  @Override
  public List<TradeMeItem> searchItems(
      URI baseUrl, String searchTerm, @Nullable Double minPrice, @Nullable Double maxPrice) {
    try {
      return doSearchItems(baseUrl, searchTerm, minPrice, maxPrice);
    } catch (Exception e) {
      throw new RuntimeException("Failed to search items", e);
    }
  }

  private List<TradeMeItem> doSearchItems(
      URI baseUrl, String searchTerm, @Nullable Double minPrice, @Nullable Double maxPrice)
      throws Exception {
    var searchUrl = buildSearchUrl(baseUrl, searchTerm, minPrice, maxPrice);
    LOGGER.info("Searching {}", searchUrl);

    var searchPage = fetchDocument(searchUrl);
    var itemUrls = extractItemUrls(searchPage);

    // check for pagination and warn if more results exist
    var paginationElements = searchPage.select(".tm-search-results__pagination");
    if (!paginationElements.isEmpty()) {
      LOGGER.warn(
          "Pagination detected on search results page - only processing first page of results."
              + " Consider narrowing search criteria to fit results on one page.");
    }

    // fetch details for each item
    var items = new ArrayList<TradeMeItem>();
    for (var itemUrl : itemUrls) {
      try {
        var itemPage = fetchDocument(itemUrl);
        var item = parseItemPage(itemPage, itemUrl);
        items.add(item);
      } catch (Exception e) {
        LOGGER.warn("Failed to fetch item details for {}: {}", itemUrl, e.getMessage());
      }
    }

    return items;
  }

  private HashSet<String> extractItemUrls(Document searchPage) {
    var itemUrls = new HashSet<String>();
    var listings = searchPage.select("a[href*='/listing/']");

    for (Element listing : listings) {
      var itemUrl = listing.attr("abs:href");
      if (!itemUrl.isEmpty()) {
        itemUrls.add(itemUrl);
      }
    }

    return itemUrls;
  }

  private TradeMeItem parseItemPage(Document itemPage, String url) {
    // extract title using specific CSS selectors
    var titleElements =
        itemPage.select(
            "h1.tm-marketplace-buyer-options__listing_title,"
                + " h1.tm-marketplace-koru-listing__title");
    if (titleElements.isEmpty()) {
      throw new RuntimeException("Could not find title element on page: " + url);
    }
    var title = titleElements.first().text().trim();

    // extract description from listing body
    var descriptionElements =
        itemPage.select(
            ".tm-marketplace-listing-body__container, .tm-marketplace-koru-listing__body");
    if (descriptionElements.isEmpty()) {
      throw new RuntimeException("Could not find description element on page: " + url);
    }

    var description =
        descriptionElements
            .first()
            .text()
            .lines()
            .filter(line -> !line.trim().isEmpty())
            .collect(Collectors.joining("\n"));

    if (description.length() > 1000) {
      description = description.substring(0, 1000) + "...";
    }

    return new TradeMeItem(url, title, description);
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

  @VisibleForTesting
  protected Document fetchDocument(String url) throws IOException {
    return Jsoup.connect(url)
        .header(
            "Accept",
            "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8")
        .header("Accept-Language", "en-GB,en;q=0.5")
        .header("Cache-Control", "no-cache")
        .header("Pragma", "no-cache")
        .header("Sec-Fetch-Dest", "document")
        .header("Sec-Fetch-Mode", "navigate")
        .header("Sec-Fetch-Site", "none")
        .header("Sec-Fetch-User", "?1")
        .header("Upgrade-Insecure-Requests", "1")
        .userAgent(
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko)"
                + " Chrome/138.0.0.0 Safari/537.36")
        .cookie("trademeclientid", "83c194f2-c94d-47d3-8e4e-08310a663c9b")
        .cookie("_gcl_au", "1.1.624413208.1751139594")
        .cookie("tm.FrEnd.browserSize", "1024")
        .timeout(30000)
        .get();
  }
}
