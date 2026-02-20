package com.jordansimsmith.auctiontracker;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

public final class TradeMeWebsiteStubServer {
  private static final String SEARCH_HTML =
      """
      <html>
        <body>
          <div class="tm-search-results">
            <a href="/a/marketplace/sports/golf/irons/steel-shaft/listing/5337003621">Titleist iron set</a>
            <a href="/a/marketplace/sports/golf/irons/steel-shaft/listing/5337003622">Callaway iron set</a>
            <a href="/a/marketplace/sports/golf/irons/steel-shaft/listing/5337003623">Reserve listing</a>
          </div>
        </body>
      </html>
      """;

  private static final String ITEM1_HTML =
      """
      <html>
        <body>
          <h1 class="tm-marketplace-buyer-options__listing_title">Titleist iron set</h1>
          <div class="tm-marketplace-listing-body__container">
            <p>Titleist irons in good used condition.</p>
            <p>Steel shafts and standard grips.</p>
          </div>
        </body>
      </html>
      """;

  private static final String ITEM2_HTML =
      """
      <html>
        <body>
          <h1 class="tm-marketplace-koru-listing__title">Callaway iron set</h1>
          <div class="tm-marketplace-koru-listing__body">
            <p>Callaway cavity back irons with regular flex shafts.</p>
            <p>Clean club faces and playable grooves.</p>
          </div>
        </body>
      </html>
      """;

  private static final String ITEM_WITH_RESERVE_NOT_MET_HTML =
      """
      <html>
        <body>
          <h1 class="tm-marketplace-koru-listing__title">Reserve listing</h1>
          <div class="tm-marketplace-koru-listing__body">
            <p>Listing included to verify reserve filtering.</p>
          </div>
          <p class="tm-koru-auction__reserve-state">Reserve not met</p>
        </body>
      </html>
      """;

  private TradeMeWebsiteStubServer() {}

  public static void main(String[] args) throws Exception {
    var server = HttpServer.create(new InetSocketAddress(8080), 0);
    server.createContext(
        "/health",
        exchange -> {
          respond(exchange, 200, "text/plain; charset=utf-8", "ok");
        });
    server.createContext(
        "/",
        exchange -> {
          var path = exchange.getRequestURI().getPath();
          if (path.contains("/search")) {
            respond(exchange, 200, "text/html; charset=utf-8", SEARCH_HTML);
            return;
          }

          if (path.contains("/listing/")) {
            var listingId = extractListingId(path);
            var html =
                switch (listingId) {
                  case "5337003621" -> ITEM1_HTML;
                  case "5337003622" -> ITEM2_HTML;
                  case "5337003623" -> ITEM_WITH_RESERVE_NOT_MET_HTML;
                  default -> null;
                };
            if (html == null) {
              respond(exchange, 404, "text/plain; charset=utf-8", "not found");
              return;
            }
            respond(exchange, 200, "text/html; charset=utf-8", html);
            return;
          }

          respond(exchange, 404, "text/plain; charset=utf-8", "not found");
        });
    server.start();
    Thread.currentThread().join();
  }

  private static String extractListingId(String path) {
    var marker = "/listing/";
    var markerIndex = path.lastIndexOf(marker);
    if (markerIndex < 0) {
      return "";
    }
    return path.substring(markerIndex + marker.length());
  }

  private static void respond(
      HttpExchange exchange, int statusCode, String contentType, String body) throws IOException {
    var bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", contentType);
    exchange.sendResponseHeaders(statusCode, bytes.length);
    try (var output = exchange.getResponseBody()) {
      output.write(bytes);
    }
  }
}
