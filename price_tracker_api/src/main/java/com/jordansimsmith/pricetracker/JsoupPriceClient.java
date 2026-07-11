package com.jordansimsmith.pricetracker;

import com.google.common.annotations.VisibleForTesting;
import java.io.IOException;
import java.net.URI;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.random.RandomGenerator;
import javax.annotation.Nullable;
import org.jsoup.Connection;
import org.jsoup.HttpStatusException;
import org.jsoup.Jsoup;

public class JsoupPriceClient implements PriceClient {
  private static final int MAX_RETRIES = 3;
  private static final long INITIAL_BACKOFF_MS = 1000;
  private static final double BACKOFF_MULTIPLIER = 2.0;
  private static final double JITTER_FACTOR = 0.5;
  private static final long MAX_RETRY_AFTER_MS = 60_000;

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

    for (var attempt = 0; attempt < MAX_RETRIES; attempt++) {
      Long retryAfterMs = null;

      try {
        var response = fetchResponse(url.toString());
        if (response.statusCode() >= 400) {
          if (response.statusCode() == 429) {
            retryAfterMs = parseRetryAfterMs(response.header("Retry-After"));
          }
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

      if (attempt < MAX_RETRIES - 1) {
        long sleepTimeMs;
        if (retryAfterMs != null) {
          sleepTimeMs = Math.min(retryAfterMs, MAX_RETRY_AFTER_MS);
        } else {
          var jitterMs = (long) (random.nextDouble() * JITTER_FACTOR * backoffMs);
          sleepTimeMs = backoffMs + jitterMs;
        }

        try {
          TimeUnit.MILLISECONDS.sleep(sleepTimeMs);
        } catch (InterruptedException ie) {
          Thread.currentThread().interrupt();
          throw new RuntimeException("Thread interrupted during backoff", ie);
        }

        backoffMs = (long) (backoffMs * BACKOFF_MULTIPLIER);
      }
    }

    if (lastException != null) {
      throw new RuntimeException(lastException);
    }

    return null;
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

  // retry-after is either delay-seconds or an http-date per rfc 9110
  @Nullable
  private Long parseRetryAfterMs(@Nullable String retryAfter) {
    if (retryAfter == null || retryAfter.isBlank()) {
      return null;
    }

    try {
      return TimeUnit.SECONDS.toMillis(Long.parseLong(retryAfter.trim()));
    } catch (NumberFormatException e) {
      // fall through to http-date parsing
    }

    try {
      var date = ZonedDateTime.parse(retryAfter.trim(), DateTimeFormatter.RFC_1123_DATE_TIME);
      return Math.max(0, date.toInstant().toEpochMilli() - System.currentTimeMillis());
    } catch (DateTimeParseException e) {
      return null;
    }
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
