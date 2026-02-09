package com.jordansimsmith.pricetracker;

import com.google.common.annotations.VisibleForTesting;
import java.io.IOException;
import java.net.URI;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.random.RandomGenerator;
import javax.annotation.Nullable;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

public class JsoupPriceClient implements PriceClient {
  private static final int MAX_RETRIES = 3;
  private static final long INITIAL_BACKOFF_MS = 1000;
  private static final double BACKOFF_MULTIPLIER = 2.0;
  private static final double JITTER_FACTOR = 0.5;

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
      try {
        var document = fetchDocument(url.toString());
        var price = extractor.extractPrice(document);
        if (price != null) {
          return price;
        }
      } catch (Exception e) {
        lastException = e;
      }

      if (attempt < MAX_RETRIES - 1) {
        var jitterMs = (long) (random.nextDouble() * JITTER_FACTOR * backoffMs);
        var sleepTimeMs = backoffMs + jitterMs;

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
        .timeout(30000)
        .get();
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
