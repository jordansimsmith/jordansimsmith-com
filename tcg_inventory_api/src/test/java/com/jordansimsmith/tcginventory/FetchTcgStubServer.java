package com.jordansimsmith.tcginventory;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

public final class FetchTcgStubServer {
  private static final AtomicInteger sellerOfferCallCount = new AtomicInteger(0);

  private static final String SEARCH_RESPONSE =
      """
      {"searchResults":{"content":[{"id":"mtg_168_c_dom_normal"}]}}\
      """;

  private static final String CARD_RESPONSE =
      """
      {"id":"mtg_168_c_dom_normal","name":"Test Card","pricingData":{"NZ":{"tcgMarketPrice":1.50}}}\
      """;

  private static final String LISTINGS_RESPONSE =
      """
      {"searchResults":{"content":[]}}\
      """;

  private static final String SELLER_OFFERS_EMPTY =
      """
      {"content":[],"totalPages":1}\
      """;

  private static final String SELLER_OFFERS_WITH_ORDER =
      """
      {"content":[{"id":99001,"status":"ACCEPTED","currentAction":"SEND_PICKUP_ADDRESS",\
      "deliveryMode":"PICKUP","totalOfferPrice":1.50,\
      "items":[{"listing":{"id":900001,"condition":"raw-lp"},"quantity":1,"price":1.50}]}],\
      "totalPages":1}\
      """;

  private static final String UPSERT_LISTING_RESPONSE =
      """
      {"listingId":900001,"remainingQuantity":1}\
      """;

  private FetchTcgStubServer() {}

  public static void main(String[] args) throws Exception {
    var server = HttpServer.create(new InetSocketAddress(8080), 0);

    server.createContext(
        "/health", exchange -> respond(exchange, 200, "text/plain; charset=utf-8", "ok"));
    server.createContext("/v3/cards", exchange -> handleCards(exchange));
    server.createContext(
        "/v2/private/market/offers/seller",
        exchange -> {
          var response =
              sellerOfferCallCount.incrementAndGet() <= 1
                  ? SELLER_OFFERS_EMPTY
                  : SELLER_OFFERS_WITH_ORDER;
          respond(exchange, 200, "application/json", response);
        });
    server.createContext(
        "/v2/private/manage-listings",
        exchange -> respond(exchange, 200, "application/json", UPSERT_LISTING_RESPONSE));
    server.createContext(
        "/v1/manage-listings", exchange -> respond(exchange, 200, "application/json", ""));

    server.start();
    Thread.currentThread().join();
  }

  private static void handleCards(HttpExchange exchange) throws IOException {
    var query = exchange.getRequestURI().getQuery();

    if (query != null && query.contains("countryCode=")) {
      respond(exchange, 200, "application/json", LISTINGS_RESPONSE);
    } else if (query != null && query.contains("cardName=")) {
      respond(exchange, 200, "application/json", SEARCH_RESPONSE);
    } else {
      respond(exchange, 200, "application/json", CARD_RESPONSE);
    }
  }

  private static void respond(
      HttpExchange exchange, int statusCode, String contentType, String bodyText)
      throws IOException {
    var body = bodyText.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", contentType);
    exchange.sendResponseHeaders(statusCode, body.length);
    try (var output = exchange.getResponseBody()) {
      output.write(body);
    }
  }
}
