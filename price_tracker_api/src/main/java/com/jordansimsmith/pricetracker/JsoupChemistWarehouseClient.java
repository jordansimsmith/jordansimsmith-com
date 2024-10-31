package com.jordansimsmith.pricetracker;

import java.net.URI;
import org.jsoup.Jsoup;

public class JsoupChemistWarehouseClient implements ChemistWarehouseClient {
  @Override
  public double getPrice(URI url) {
    try {
      return doGetPrice(url);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  private double doGetPrice(URI url) throws Exception {
    var doc = Jsoup.connect(url.toString()).get();

    var element = doc.select(".product_details .Price .product__price");
    var price = element.text().replaceAll("\\$", "");

    return Double.parseDouble(price);
  }
}
