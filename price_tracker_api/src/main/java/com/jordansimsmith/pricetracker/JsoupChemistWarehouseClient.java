package com.jordansimsmith.pricetracker;

import java.net.URI;
import javax.annotation.Nullable;
import org.jsoup.Jsoup;

public class JsoupChemistWarehouseClient implements ChemistWarehouseClient {
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

    var element = doc.select(".product_details .Price .product__price");
    if (element.isEmpty()) {
      return null;
    }
    var price = element.text().replaceAll("\\$", "");

    return Double.parseDouble(price);
  }
}
