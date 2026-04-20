package com.jordansimsmith.footballcalendar;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

public final class NrfMockServer {
  private static final String FIXTURES_RESPONSE =
      """
      {
        "Fixtures": [
          {
            "Id": 6334635,
            "HomeTeamName": "Dusties",
            "HomeOrgName": "Bucklands Beach AFC",
            "AwayTeamName": "Flamingos",
            "AwayOrgName": "Ellerslie AFC",
            "From": "2026-04-18T13:00:00",
            "To": "2026-04-18T15:00:00",
            "VenueName": "Lloyd Elsmore Pk: Field 2",
            "VenueAddress": "Lloyd Elsmore Park",
            "LocationLat": -36.910553,
            "LocationLng": 174.90271,
            "StatusName": "Confirmed"
          }
        ]
      }
      """;

  private NrfMockServer() {}

  public static void main(String[] args) throws Exception {
    var server = HttpServer.create(new InetSocketAddress(8080), 0);
    server.createContext(
        "/health", exchange -> respond(exchange, "text/plain; charset=utf-8", "ok"));
    server.createContext(
        "/api/v2/competition/widget/fixture/Dates",
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
