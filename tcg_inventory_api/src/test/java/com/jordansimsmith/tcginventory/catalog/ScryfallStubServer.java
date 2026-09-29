package com.jordansimsmith.tcginventory.catalog;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

public class ScryfallStubServer {
  private static final String CARD_ID = "4eaac4fd-95f5-4f38-b593-0101e79a20f9";
  private static final String ALTERNATE_ID = "c83275d7-0d4e-4e25-b8b8-433e9d2a9290";
  private static final String ORACLE_ID = "4457ed35-7c10-48c8-9776-456485fdf070";

  public static void main(String[] args) throws Exception {
    var server = HttpServer.create(new InetSocketAddress(8080), 0);
    server.createContext("/health", exchange -> respond(exchange, 200, "ok"));
    server.createContext("/cards", ScryfallStubServer::handleCards);
    server.start();
    Thread.currentThread().join();
  }

  private static void handleCards(HttpExchange exchange) throws IOException {
    var path = exchange.getRequestURI().getPath();
    if (path.equals("/cards/search")) {
      respond(
          exchange,
          200,
          """
          {"object":"list","data":[%s,%s],"has_more":false}
          """
              .formatted(
                  card(CARD_ID, "Lightning Bolt", "sta", "42"),
                  card(ALTERNATE_ID, "Lightning Bolt", "2xm", "117")));
      return;
    }
    var id = path.substring("/cards/".length());
    if (!CARD_ID.equals(id) && !ALTERNATE_ID.equals(id)) {
      respond(exchange, 404, "{\"object\":\"error\",\"details\":\"not found\"}");
      return;
    }
    respond(
        exchange,
        200,
        card(
            id,
            id.equals(CARD_ID) ? "Lightning Bolt" : "Lightning Bolt",
            id.equals(CARD_ID) ? "sta" : "2xm",
            id.equals(CARD_ID) ? "42" : "117"));
  }

  private static String card(String id, String name, String setCode, String collectorNumber) {
    return """
    {"id":"%s","name":"%s","lang":"en","set":"%s","set_name":"Test Set",\
    "collector_number":"%s","finishes":["nonfoil","foil"],"games":["paper"],\
    "image_uris":{"small":"https://cards.scryfall.io/small/%s.jpg",\
    "normal":"https://cards.scryfall.io/normal/%s.jpg"},\
    "prints_search_uri":"http://scryfall-stub:8080/cards/search?order=released&q=oracleid%%3A%s&unique=prints"}
    """
        .formatted(id, name, setCode, collectorNumber, id, id, ORACLE_ID);
  }

  private static void respond(HttpExchange exchange, int status, String response)
      throws IOException {
    var body = response.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
    exchange.sendResponseHeaders(status, body.length);
    exchange.getResponseBody().write(body);
    exchange.close();
  }
}
