package com.jordansimsmith.footballcalendar;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

public final class SubfootballStubServer {
  private static final String FIXTURES_ICAL =
      """
      BEGIN:VCALENDAR
      VERSION:2.0
      BEGIN:VEVENT
      DESCRIPTION:Field: Yellow\\nRound: 1\\nMan I love football and Hugh Jazz
      DTSTART:20261027T045000Z
      LOCATION:Auckland Domain\\, Auckland
      SUMMARY:Round 1 - Man I love football vs Hugh Jazz
      UID:summer-fixture-1
      END:VEVENT
      END:VCALENDAR
      """;

  private SubfootballStubServer() {}

  public static void main(String[] args) throws Exception {
    var server = HttpServer.create(new InetSocketAddress(8080), 0);
    server.createContext(
        "/health", exchange -> respond(exchange, "text/plain; charset=utf-8", "ok"));
    server.createContext(
        "/teams/calendar/4517",
        exchange -> respond(exchange, "text/calendar; charset=utf-8", FIXTURES_ICAL));
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
