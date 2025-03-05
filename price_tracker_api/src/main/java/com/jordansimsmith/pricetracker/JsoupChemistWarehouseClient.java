package com.jordansimsmith.pricetracker;

import java.net.URI;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;
import org.jsoup.Jsoup;

public class JsoupChemistWarehouseClient implements ChemistWarehouseClient {
  private static final int MAX_RETRIES = 3;
  private static final long INITIAL_BACKOFF_MS = 1000;
  private static final double BACKOFF_MULTIPLIER = 2.0;
  private static final double JITTER_FACTOR = 0.5;
  private static final int TIMEOUT_MS = 30000;

  private static final List<String> USER_AGENTS =
      List.of(
          "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko)"
              + " Chrome/109.0.0.0 Safari/537.36",
          "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko)"
              + " Chrome/109.0.0.0 Safari/537.36",
          "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:109.0) Gecko/20100101 Firefox/109.0",
          "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko)"
              + " Version/16.3 Safari/605.1.15",
          "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/109.0.0.0"
              + " Safari/537.36");

  private final Random random;

  public JsoupChemistWarehouseClient(Random random) {
    this.random = random;
  }

  @Override
  @Nullable
  public Double getPrice(URI url) {
    var backoffMs = INITIAL_BACKOFF_MS;
    Exception lastException = null;

    for (var attempt = 0; attempt < MAX_RETRIES; attempt++) {
      try {
        var price = doGetPrice(url);
        if (price != null) {
          return price;
        }
      } catch (Exception e) {
        lastException = e;
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
      throw new RuntimeException(lastException);
    }

    return null;
  }

  @Nullable
  private Double doGetPrice(URI url) throws Exception {
    var userAgent = USER_AGENTS.get(random.nextInt(USER_AGENTS.size()));

    var connection =
        Jsoup.connect(url.toString())
            .userAgent(userAgent)
            .header(
                "Accept",
                "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
            .header("Accept-Language", "en-US,en;q=0.5")
            .header("Accept-Encoding", "gzip, deflate, br")
            .header("DNT", "1")
            .header("Connection", "keep-alive")
            .header("Upgrade-Insecure-Requests", "1")
            .header("Sec-Fetch-Dest", "document")
            .header("Sec-Fetch-Mode", "navigate")
            .header("Sec-Fetch-Site", "none")
            .header("Sec-Fetch-User", "?1")
            .header("Cache-Control", "max-age=0")
            .referrer("https://www.google.com")
            .timeout(TIMEOUT_MS)
            .followRedirects(true);

    var doc = connection.get();

    var element = doc.select(".product_details .Price .product__price");
    if (element.isEmpty()) {
      return null;
    }
    var price = element.text().replaceAll("\\$", "");

    return Double.parseDouble(price);
  }
}
