package com.jordansimsmith.pricetracker;

import com.google.common.annotations.VisibleForTesting;
import java.io.IOException;
import java.net.URI;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.random.RandomGenerator;
import javax.annotation.Nullable;
import org.jsoup.Connection;
import org.jsoup.HttpStatusException;
import org.jsoup.Jsoup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class JsoupPriceClient implements PriceClient {
  private static final Logger LOGGER = LoggerFactory.getLogger(JsoupPriceClient.class);

  private static final int MAX_ATTEMPTS = 3;
  private static final long INITIAL_BACKOFF_MS = 1000;
  private static final double BACKOFF_MULTIPLIER = 2.0;
  private static final double JITTER_FACTOR = 0.5;
  private static final int MAX_LOGGED_BODY_CHARS = 1000;

  private final RandomGenerator random;
  private final Map<String, PriceExtractor> priceExtractors;

  public JsoupPriceClient(RandomGenerator random, Map<String, PriceExtractor> priceExtractors) {
    this.random = random;
    this.priceExtractors = priceExtractors;
  }

  @Override
  @Nullable
  public Double getPrice(URI url) {
    PriceExtractor extractor = getExtractorForUrl(url);
    if (extractor == null) {
      throw new IllegalArgumentException("Unsupported website: " + url.getHost());
    }

    var backoffMs = INITIAL_BACKOFF_MS;
    Exception lastException = null;

    for (var attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
      try {
        var response = fetchResponse(url.toString());
        if (response.statusCode() / 100 != 2) {
          logErrorResponse(url, response);
          throw new HttpStatusException(
              "HTTP error fetching URL", response.statusCode(), url.toString());
        }
        var price = extractor.extractPrice(response.parse());
        if (price != null) {
          return price;
        }
      } catch (Exception e) {
        lastException = e;
      }

      if (attempt < MAX_ATTEMPTS - 1) {
        var jitterMs = (long) (random.nextDouble() * JITTER_FACTOR * backoffMs);
        try {
          TimeUnit.MILLISECONDS.sleep(backoffMs + jitterMs);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new RuntimeException("Thread interrupted during backoff", e);
        }
        backoffMs = (long) (backoffMs * BACKOFF_MULTIPLIER);
      }
    }

    if (lastException != null) {
      throw new RuntimeException(lastException);
    }

    return null;
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
