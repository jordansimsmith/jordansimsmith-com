package com.jordansimsmith.pricetracker;

import java.net.URI;
import javax.annotation.Nullable;
import org.jsoup.Jsoup;

public class JsoupNzProteinClient implements NzProteinClient {
  @Override
  @Nullable
  public Double getPrice(URI url) {
    try {
      return doGetPrice(url);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  @Nullable
  private Double doGetPrice(URI url) throws Exception {
    var doc = Jsoup.connect(url.toString()).get();
    var price = doc.select("[itemprop=\"price\"]");

    if (price.size() != 1) {
      return null;
    }

    return Double.parseDouble(price.text());
  }
}
