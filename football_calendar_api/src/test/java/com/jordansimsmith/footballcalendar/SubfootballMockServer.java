package com.jordansimsmith.footballcalendar;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

public final class SubfootballMockServer {
  private static final String FIXTURES_ICAL =
      """
      BEGIN:VCALENDAR
      VERSION:2.0
      BEGIN:VEVENT
      DESCRIPTION:Field: Black\\nRound: 1\\nMan I Love Football and Swede as Bro FC
      DTSTART:20251028T045000Z
      LOCATION:Auckland Domain\\, Auckland
      SUMMARY:Round 1 - Man I Love Football vs Swede as Bro FC
      UID:8c19b36f-0b5d-41f9-aa9c-2779b6fff277
      END:VEVENT
      END:VCALENDAR
      """;

  private SubfootballMockServer() {}

  public static void main(String[] args) throws Exception {
    var server = HttpServer.create(new InetSocketAddress(8080), 0);
    server.createContext(
        "/health", exchange -> respond(exchange, "text/plain; charset=utf-8", "ok"));
    server.createContext(
        "/teams/calendar/4326",
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
