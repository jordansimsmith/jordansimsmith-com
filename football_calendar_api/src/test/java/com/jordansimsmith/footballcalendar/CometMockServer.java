package com.jordansimsmith.footballcalendar;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

public final class CometMockServer {
  private static final String FIXTURES_RESPONSE =
      """
      {
        "fixtures": [
          {
            "Id": "2716942185",
            "HomeTeamNameAbbr": "Bucklands Beach Bucks M5",
            "AwayTeamNameAbbr": "Ellerslie AFC Flamingoes M",
            "Date": "2025-04-05T15:00:00",
            "VenueName": "Lloyd Elsmore Park 2",
            "Address": "2 Bells Avenue",
            "Latitude": "-36.9053315",
            "Longitude": "174.8997797",
            "Status": "CONFIRMED"
          }
        ]
      }
      """;

  private CometMockServer() {}

  public static void main(String[] args) throws Exception {
    var server = HttpServer.create(new InetSocketAddress(8080), 0);
    server.createContext(
        "/health", exchange -> respond(exchange, "text/plain; charset=utf-8", "ok"));
    server.createContext(
        "/api/1.0/competition/cometwidget/filteredfixtures",
        exchange -> respond(exchange, "application/json; charset=utf-8", FIXTURES_RESPONSE));
    server.start();
    Thread.currentThread().join();
  }

  private static void respond(HttpExchange exchange, String contentType, String bodyText)
      throws IOException {
    var body = bodyText.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", contentType);
    exchange.sendResponseHeaders(200, body.length);
    try (var output = exchange.getResponseBody()) {
      output.write(body);
    }
  }
}
