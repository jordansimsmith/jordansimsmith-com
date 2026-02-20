package com.jordansimsmith.pricetracker;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;

public final class PriceTrackerWebsiteStubServer {
  record HostFixtures(double defaultPrice, Map<String, Double> overridesByRequestKey) {}

  static final String CHEMIST_WAREHOUSE_STUB_HOST = "chemist-warehouse-stub";
  static final String NZ_PROTEIN_STUB_HOST = "nz-protein-stub";
  static final String NZ_MUSCLE_STUB_HOST = "nz-muscle-stub";
  static final String CHEMIST_WAREHOUSE_TEMPLATE =
      """
      <html>
        <body>
          <div class="product_details">
            <div class="Price">
              <div class="product__price">%s</div>
            </div>
          </div>
        </body>
      </html>
      """;
  static final String NZ_PROTEIN_TEMPLATE =
      """
      <html>
        <body>
          <div itemprop="price">%s</div>
        </body>
      </html>
      """;
  static final String NZ_MUSCLE_TEMPLATE =
      """
      <html>
        <body>
          <div class="price price--show-badge">
            <div class="price__sale">
              <span class="price-item price-item--sale price-item--last">%s</span>
            </div>
            <div class="price__regular">
              <span class="price-item price-item--regular price-item--last">%s</span>
            </div>
          </div>
        </body>
      </html>
      """;

  static final Map<String, HostFixtures> HOST_FIXTURES =
      Map.of(
          CHEMIST_WAREHOUSE_STUB_HOST,
          new HostFixtures(
              52.0, Map.of("/buy/98676/inc-100-dynamic-whey-cookies-and-cream-flavour-2kg", 49.99)),
          NZ_PROTEIN_STUB_HOST,
          new HostFixtures(84.95, Map.of()),
          NZ_MUSCLE_STUB_HOST,
          new HostFixtures(
              92.5, Map.of("/products/shotgun-whey-protein?variant=51471561162933", 89.9)));

  private PriceTrackerWebsiteStubServer() {}

  public static void main(String[] args) throws Exception {
    var server = HttpServer.create(new InetSocketAddress(8080), 0);
    server.createContext(
        "/health",
        exchange -> {
          var body = "ok".getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "text/plain; charset=utf-8");
          exchange.sendResponseHeaders(200, body.length);
          try (var output = exchange.getResponseBody()) {
            output.write(body);
          }
        });
    server.createContext(
        "/",
        exchange -> {
          var hostHeader = exchange.getRequestHeaders().getFirst("Host").toLowerCase(Locale.ROOT);
          var host =
              hostHeader.contains(":")
                  ? hostHeader.substring(0, hostHeader.indexOf(':'))
                  : hostHeader;
          var path = exchange.getRequestURI().getRawPath();
          var query = exchange.getRequestURI().getRawQuery();
          var requestKey = query == null ? path : path + "?" + query;

          var hostFixtures = HOST_FIXTURES.get(host);
          var price =
              hostFixtures
                  .overridesByRequestKey()
                  .getOrDefault(requestKey, hostFixtures.defaultPrice());
          var formattedPrice = "$%.2f".formatted(price);
          var html =
              switch (host) {
                case CHEMIST_WAREHOUSE_STUB_HOST ->
                    CHEMIST_WAREHOUSE_TEMPLATE.formatted(formattedPrice);
                case NZ_PROTEIN_STUB_HOST -> NZ_PROTEIN_TEMPLATE.formatted(formattedPrice);
                case NZ_MUSCLE_STUB_HOST ->
                    NZ_MUSCLE_TEMPLATE.formatted(formattedPrice, formattedPrice);
                default -> "";
              };

          var body = html.getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
          exchange.sendResponseHeaders(200, body.length);
          try (var output = exchange.getResponseBody()) {
            output.write(body);
          }
        });
    server.start();
    Thread.currentThread().join();
  }
}
