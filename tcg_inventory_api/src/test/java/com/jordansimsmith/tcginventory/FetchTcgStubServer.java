package com.jordansimsmith.tcginventory;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

public final class FetchTcgStubServer {
  private static final AtomicInteger sellerOfferCallCount = new AtomicInteger(0);
  private static final AtomicInteger imageUploadCount = new AtomicInteger(0);

  private static volatile boolean listingStored;
  private static volatile String lastFrontImageJson;
  private static volatile String lastAdditionalImagesJson;

  private static final String SEARCH_RESPONSE =
      """
      {"searchResults":{"content":[{"id":"mtg_168_c_dom_normal"}]}}\
      """;

  private static final String HIT_SEARCH_RESPONSE =
      """
      {"searchResults":{"content":[{"id":"mtg_hit_c_dom_normal"}]}}\
      """;

  private static final String CARD_RESPONSE =
      """
      {"id":"mtg_168_c_dom_normal","name":"Test Card","pricingData":{"NZ":{"tcgMarketPrice":1.50}},\
      "externalReferences":{"scryfallId":"a1b2c3d4-e5f6-7890-abcd-ef1234567890"}}\
      """;

  private static final String HIT_CARD_RESPONSE =
      """
      {"id":"mtg_hit_c_dom_normal","name":"Test Hit","pricingData":{"NZ":{"tcgMarketPrice":60.00}},\
      "externalReferences":{"scryfallId":"aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"}}\
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
      "items":[{"listing":{"id":900001,"condition":"raw-lp","listedPrice":2.00},"quantity":1,"price":1.50}]}],\
      "totalPages":1}\
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
        "/v2/private/manage-listings/uploadListingImage",
        exchange -> handleUploadListingImage(exchange));
    server.createContext("/v2/private/manage-listings", exchange -> handleUpsertListing(exchange));
    server.createContext("/v1/manage-listings", exchange -> handleManageListings(exchange));

    server.start();
    Thread.currentThread().join();
  }

  private static void handleCards(HttpExchange exchange) throws IOException {
    var query = exchange.getRequestURI().getQuery();
    var path = exchange.getRequestURI().getPath();

    if (query != null && query.contains("countryCode=")) {
      respond(exchange, 200, "application/json", LISTINGS_RESPONSE);
    } else if (query != null && query.contains("cardName=")) {
      var searchResponse =
          query.contains("cardName=Test+Hit") || query.contains("cardName=Test%20Hit")
              ? HIT_SEARCH_RESPONSE
              : SEARCH_RESPONSE;
      respond(exchange, 200, "application/json", searchResponse);
    } else if (path.endsWith("/mtg_hit_c_dom_normal")) {
      respond(exchange, 200, "application/json", HIT_CARD_RESPONSE);
    } else {
      respond(exchange, 200, "application/json", CARD_RESPONSE);
    }
  }

  private static void handleUploadListingImage(HttpExchange exchange) throws IOException {
    exchange.getRequestBody().readAllBytes();
    var imageUrl =
        "https://listing-img.fetchtcg.com/stub/listing/"
            + imageUploadCount.incrementAndGet()
            + ".jpg";
    respond(exchange, 200, "application/json", "{\"imageUrl\":\"" + imageUrl + "\"}");
  }

  private static void handleUpsertListing(HttpExchange exchange) throws IOException {
    var requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    lastFrontImageJson = extractJsonField(requestBody, "frontImage");
    lastAdditionalImagesJson = extractJsonField(requestBody, "additionalImages");
    listingStored = true;
    respond(exchange, 200, "application/json", listingJson());
  }

  private static void handleManageListings(HttpExchange exchange) throws IOException {
    if ("DELETE".equalsIgnoreCase(exchange.getRequestMethod())) {
      respond(exchange, 200, "application/json", "");
      return;
    }
    if (!listingStored) {
      respond(exchange, 200, "application/json", "{\"content\":[]}");
      return;
    }
    respond(exchange, 200, "application/json", "{\"content\":[" + listingJson() + "]}");
  }

  private static String listingJson() {
    var listing = new StringBuilder("{\"listingId\":900001,\"remainingQuantity\":1");
    if (lastFrontImageJson != null) {
      listing.append(",\"frontImage\":").append(lastFrontImageJson);
    }
    if (lastAdditionalImagesJson != null) {
      listing.append(",\"additionalImages\":").append(lastAdditionalImagesJson);
    }
    return listing.append("}").toString();
  }

  private static String extractJsonField(String json, String field) {
    var matcher =
        Pattern.compile("\"" + field + "\"\\s*:\\s*(\"(?:\\\\.|[^\"\\\\])*\"|\\[[^\\]]*\\]|null)")
            .matcher(json);
    return matcher.find() ? matcher.group(1) : null;
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
