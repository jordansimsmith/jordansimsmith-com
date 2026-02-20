package com.jordansimsmith.immersiontracker;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

public final class ImmersionTrackerSpotifyAccountsStubServer {
  private static final String TOKEN_RESPONSE =
      """
      {
        "access_token": "mock-spotify-token",
        "token_type": "Bearer",
        "expires_in": 3600
      }
      """;

  private ImmersionTrackerSpotifyAccountsStubServer() {}

  public static void main(String[] args) throws Exception {
    var server = HttpServer.create(new InetSocketAddress(8080), 0);
    server.createContext(
        "/health", exchange -> respond(exchange, "text/plain; charset=utf-8", "ok"));
    server.createContext(
        "/api/token",
        exchange -> respond(exchange, "application/json; charset=utf-8", TOKEN_RESPONSE));
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
