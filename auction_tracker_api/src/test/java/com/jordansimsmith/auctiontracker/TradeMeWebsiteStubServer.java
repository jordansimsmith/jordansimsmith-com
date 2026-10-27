package com.jordansimsmith.auctiontracker;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class TradeMeWebsiteStubServer {
  private static final ObjectMapper MAPPER = new ObjectMapper();

  public static void main(String[] args) throws Exception {
    var server = HttpServer.create(new InetSocketAddress(8080), 0);
    server.createContext("/health", exchange -> respond(exchange, 200, "ok", "text/plain"));
    server.createContext(
        "/",
        exchange -> {
          var path = exchange.getRequestURI().getPath();
          if (path.equals("/v1/search/general.json")) {
            respond(exchange, 200, searchResponse(exchange.getRequestURI()), "application/json");
          } else if (path.matches("/v1/listings/[0-9]+\\.json")) {
            var id = path.substring("/v1/listings/".length(), path.length() - ".json".length());
            var listing = listingResponse(id);
            if (listing == null) {
              respond(exchange, 404, errorResponse("Listing not found"), "application/json");
            } else {
              respond(exchange, 200, listing, "application/json");
            }
          } else {
            respond(exchange, 404, errorResponse("Not found"), "application/json");
          }
        });
    server.start();
    Thread.currentThread().join();
  }

  private static Map<String, Object> searchResponse(URI uri) {
    var query = queryParameters(uri);
    var category = query.get("canonical_path");
    var ids =
        category != null && category.endsWith("/pokemon")
            ? List.of(5337003624L)
            : List.of(5337003621L, 5337003622L, 5337003623L);
    var response = new LinkedHashMap<String, Object>();
    response.put("List", ids.stream().map(id -> searchListing(id, category)).toList());
    response.put("TotalCount", ids.size());
    response.put("Page", Integer.parseInt(query.getOrDefault("page", "1")));
    response.put(
        "PageSize", ids.size() == 0 ? 0 : Integer.parseInt(query.getOrDefault("rows", "56")));
    return response;
  }

  private static Map<String, Object> searchListing(long id, String category) {
    var path = category + "/listing/" + id;
    return Map.of("ListingId", id, "CanonicalPath", path);
  }

  private static Object listingResponse(String id) {
    var root = MAPPER.createObjectNode();
    var path =
        "/marketplace/gaming/trading-cards/"
            + (id.equals("5337003624") ? "pokemon" : "magic")
            + "/listing/"
            + id;
    var listing = root;
    switch (id) {
      case "5337003621" -> {
        listing.put("Title", "Titleist iron set");
        listing.put(
            "Body", "Titleist irons in good used condition.\nSteel shafts and standard grips.");
        listing.put("StartPrice", 1);
        listing.put("BuyNowPrice", 150);
        listing.put("MaxBidAmount", 20);
        listing.put("ReserveState", 1);
        listing.putObject("Member").put("Nickname", "allowed-seller");
      }
      case "5337003622" -> {
        listing.put("Title", "Callaway iron set");
        listing.put(
            "Body",
            "Callaway cavity back irons with regular flex shafts.\n"
                + "Clean club faces and playable grooves.");
        listing.put("StartPrice", 0);
        listing.put("BuyNowPrice", 200);
        listing.put("ReserveState", 3);
        listing.putObject("Member").put("Nickname", "roseshade");
      }
      case "5337003623" -> {
        listing.put("Title", "Reserve not met test listing");
        listing.put("Body", "A listing that must be skipped before persistence.");
        listing.put("StartPrice", 1);
        listing.put("ReserveState", 2);
        listing.putObject("Member").put("Nickname", "allowed-seller");
      }
      case "5337003624" -> {
        listing.put("Title", "Pokemon bulk collection");
        listing.put(
            "Body",
            "500 English and Japanese Pokemon cards from one fixed collection.\n"
                + "Mostly near mint and lightly played.");
        listing.put("StartPrice", 1);
        listing.put("BuyNowPrice", 150);
        listing.put("ReserveState", 1);
        listing.putObject("Member").put("Nickname", "allowed-seller");
      }
      default -> {
        return null;
      }
    }
    listing.put("ListingId", Long.parseLong(id));
    listing.put("CanonicalPath", path);
    return root;
  }

  private static Map<String, String> queryParameters(URI uri) {
    var parameters = new LinkedHashMap<String, String>();
    for (var part : uri.getRawQuery().split("&")) {
      var pair = part.split("=", 2);
      var key = URLDecoder.decode(pair[0], StandardCharsets.UTF_8);
      var value = pair.length == 2 ? URLDecoder.decode(pair[1], StandardCharsets.UTF_8) : "";
      parameters.put(key, value);
    }
    return parameters;
  }

  private static Object errorResponse(String message) {
    return Map.of("Error", "NotFound", "ErrorDescription", message);
  }

  private static void respond(HttpExchange exchange, int status, Object body, String contentType)
      throws IOException {
    var bytes =
        body instanceof String text
            ? text.getBytes(StandardCharsets.UTF_8)
            : MAPPER.writeValueAsBytes(body);
    exchange.getResponseHeaders().add("Content-Type", contentType + "; charset=utf-8");
    exchange.sendResponseHeaders(status, bytes.length);
    try (var output = exchange.getResponseBody()) {
      output.write(bytes);
    }
  }
}
