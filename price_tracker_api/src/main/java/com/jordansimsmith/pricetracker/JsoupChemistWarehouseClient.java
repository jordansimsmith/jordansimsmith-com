package com.jordansimsmith.pricetracker;

import java.net.URI;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;
import org.jsoup.Jsoup;

public class JsoupChemistWarehouseClient implements ChemistWarehouseClient {
  private static final int MAX_RETRIES = 3;
  private static final long INITIAL_BACKOFF_MS = 1000;
  private static final double BACKOFF_MULTIPLIER = 2.0;
  private static final double JITTER_FACTOR = 0.5;

  private final Random random;

  public JsoupChemistWarehouseClient(Random random) {
    this.random = random;
  }

  @Override
  @Nullable
  public Double getPrice(URI url) {
    var backoffMs = INITIAL_BACKOFF_MS;
    Exception lastException = null;

    for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
      try {
        var price = doGetPrice(url);
        if (price != null) {
          return price;
        }
      } catch (Exception e) {
        lastException = e;
      }

      if (attempt == MAX_RETRIES) {
        break;
      }

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

    if (lastException != null) {
      throw lastException;
    }

    return null;
  }

  @Nullable
  private Double doGetPrice(URI url) throws Exception {
    var doc = Jsoup.connect(url.toString()).get();

    var element = doc.select(".product_details .Price .product__price");
    if (element.isEmpty()) {
      return null;
    }
    var price = element.text().replaceAll("\\$", "");

    return Double.parseDouble(price);
  }
}
