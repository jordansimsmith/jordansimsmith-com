package com.jordansimsmith.pricetracker;

import java.net.URI;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.random.RandomGenerator;
import javax.annotation.Nullable;
import org.jsoup.Jsoup;

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
        var document = Jsoup.connect(url.toString()).get();
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

  @Nullable
  private PriceExtractor getExtractorForUrl(URI url) {
    String host = url.getHost().toLowerCase();

    if (priceExtractors.containsKey(host)) {
      return priceExtractors.get(host);
    }

    return null;
  }
}
