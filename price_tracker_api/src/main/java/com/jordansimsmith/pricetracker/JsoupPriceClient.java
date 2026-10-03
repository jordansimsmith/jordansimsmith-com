package com.jordansimsmith.pricetracker;

import com.google.common.annotations.VisibleForTesting;
import java.io.IOException;
import java.net.URI;
import java.util.Map;
import javax.annotation.Nullable;
import org.jsoup.Connection;
import org.jsoup.HttpStatusException;
import org.jsoup.Jsoup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class JsoupPriceClient implements PriceClient {
  private static final Logger LOGGER = LoggerFactory.getLogger(JsoupPriceClient.class);

  private static final int MAX_LOGGED_BODY_CHARS = 1000;

  private final Map<String, PriceExtractor> priceExtractors;

  public JsoupPriceClient(Map<String, PriceExtractor> priceExtractors) {
    this.priceExtractors = priceExtractors;
  }

  @Override
  @Nullable
  public Double getPrice(URI url) {
    PriceExtractor extractor = getExtractorForUrl(url);
    if (extractor == null) {
      throw new IllegalArgumentException("Unsupported website: " + url.getHost());
    }

    try {
      return getPriceImpl(url, extractor);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  @Nullable
  private Double getPriceImpl(URI url, PriceExtractor extractor) throws IOException {
    var response = fetchResponse(url.toString());
    if (response.statusCode() / 100 != 2) {
      logErrorResponse(url, response);
      throw new HttpStatusException(
          "HTTP error fetching URL", response.statusCode(), url.toString());
    }
    return extractor.extractPrice(response.parse());
  }

  private void logErrorResponse(URI url, Connection.Response response) {
    String body;
    try {
      body = response.body();
    } catch (Exception e) {
      body = "<failed to read body: " + e.getMessage() + ">";
    }
    if (body.length() > MAX_LOGGED_BODY_CHARS) {
      body = body.substring(0, MAX_LOGGED_BODY_CHARS) + "... (truncated)";
    }

    LOGGER.warn(
        "http {} fetching url '{}', headers: {}, body: {}",
        response.statusCode(),
        url,
        response.headers(),
        body);
  }

  @VisibleForTesting
  protected Connection.Response fetchResponse(String url) throws IOException {
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
        .timeout(30000)
        .ignoreHttpErrors(true)
        .execute();
  }

  @Nullable
  private PriceExtractor getExtractorForUrl(URI url) {
    String host = url.getHost().toLowerCase();

    if (priceExtractors.containsKey(host)) {
      return priceExtractors.get(host);
    }

    return null;
  }
}
