package com.jordansimsmith.auctiontracker;

import com.google.common.collect.Iterables;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import org.openqa.selenium.By;
import org.openqa.selenium.PageLoadStrategy;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SeleniumTradeMeClient implements TradeMeClient {
  private static final Logger LOGGER = LoggerFactory.getLogger(SeleniumTradeMeClient.class);

  private static final Duration WAIT_TIMEOUT = Duration.ofSeconds(30);

  private static final By RESULT_COUNT_SELECTOR =
      By.cssSelector("h3.tm-search-header-result-count__heading");
  private static final By TITLE_SELECTOR =
      By.cssSelector(
          "h1.tm-marketplace-buyer-options__listing_title, h1.tm-marketplace-koru-listing__title");
  private static final By DESCRIPTION_SELECTOR =
      By.cssSelector(".tm-marketplace-listing-body__container, .tm-marketplace-koru-listing__body");

  @Override
  public List<TradeMeItem> searchItems(
      URI baseUrl, String searchTerm, @Nullable Double minPrice, @Nullable Double maxPrice) {
    var options = new ChromeOptions();
    options.setPageLoadStrategy(PageLoadStrategy.NORMAL);
    options.addArguments("--headless");
    options.addArguments("--no-sandbox");
    options.addArguments("--disable-dev-shm-usage");
    options.addArguments("--disable-gpu");
    options.addArguments("--window-size=1920,1080");
    var driver = new ChromeDriver(options);

    try {
      return doSearchItems(driver, baseUrl, searchTerm, minPrice, maxPrice);
    } catch (Exception e) {
      LOGGER.error("Failed to search items", e);
      throw new RuntimeException("Failed to search items", e);
    } finally {
      driver.quit();
    }
  }

  private List<TradeMeItem> doSearchItems(
      WebDriver driver,
      URI baseUrl,
      String searchTerm,
      @Nullable Double minPrice,
      @Nullable Double maxPrice)
      throws Exception {
    var wait = new WebDriverWait(driver, WAIT_TIMEOUT);

    // extract all the item urls from all search pages
    var itemUrls = new HashSet<String>();
    var currentPage = 1;
    while (currentPage < 5) {
      var searchUrl = buildSearchUrl(baseUrl, searchTerm, minPrice, maxPrice, currentPage);
      LOGGER.info("Searching {}", searchUrl);
      driver.get(searchUrl);

      // wait for search results to load - look for the result count header
      wait.until(webDriver -> !webDriver.findElements(RESULT_COUNT_SELECTOR).isEmpty());

      // find all listing links on this page
      var listings = driver.findElements(By.cssSelector("a[href*='/listing/']"));

      // if no listings found on this page, we've reached the end
      if (listings.isEmpty()) {
        break;
      }

      // extract URLs from this page
      for (var listing : listings) {
        var itemUrl = listing.getAttribute("href");
        itemUrls.add(itemUrl);
      }

      currentPage++;
    }

    // get item details for each url
    var items = new ArrayList<TradeMeItem>();
    for (var itemUrl : itemUrls) {
      var item = fetchItemDetails(driver, wait, itemUrl);
      items.add(item);
    }

    return items;
  }

  private TradeMeItem fetchItemDetails(WebDriver driver, WebDriverWait wait, String url) {
    LOGGER.info("Fetching {}", url);
    driver.get(url);

    // wait for the page to load - look for the listing title
    wait.until(
        webDriver ->
            !webDriver.findElements(TITLE_SELECTOR).isEmpty()
                && !webDriver.findElements(DESCRIPTION_SELECTOR).isEmpty());

    // extract title using specific CSS class
    var titles = driver.findElements(TITLE_SELECTOR);
    var title = Iterables.getOnlyElement(titles).getText().trim();

    // extract all text from the listing details container
    var details = driver.findElements(DESCRIPTION_SELECTOR);
    var detail = Iterables.getOnlyElement(details);

    var description =
        detail.getText().lines().filter(l -> !l.isEmpty()).collect(Collectors.joining("\n"));
    if (description.length() > 1000) {
      description = description.substring(0, 1000) + "...";
    }

    return new TradeMeItem(url, title, description);
  }

  private String buildSearchUrl(
      URI baseUrl,
      String searchTerm,
      @Nullable Double minPrice,
      @Nullable Double maxPrice,
      int page) {
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

    if (page > 1) {
      url += "&page=" + page;
    }

    url += "&sort_order=titleasc";

    return url;
  }
}
