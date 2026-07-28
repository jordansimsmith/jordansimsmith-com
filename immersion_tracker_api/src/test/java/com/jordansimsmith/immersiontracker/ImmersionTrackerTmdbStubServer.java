package com.jordansimsmith.immersiontracker;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

public final class ImmersionTrackerTmdbStubServer {
  private static final String MOVIE_916224_RESPONSE =
      """
      {
        "id": 916224,
        "title": "Suzume",
        "original_title": "すずめの戸締まり",
        "poster_path": "/vIeu8WysZrTSFb2uhPViKjX9EcC.jpg",
        "runtime": 122
      }
      """;

  private static final String MOVIE_372058_RESPONSE =
      """
      {
        "id": 372058,
        "title": "Your Name.",
        "original_title": "君の名は。",
        "poster_path": "/q719jXXEzOoYaps6babgKnONONX.jpg",
        "runtime": 106
      }
      """;

  private ImmersionTrackerTmdbStubServer() {}

  public static void main(String[] args) throws Exception {
    var server = HttpServer.create(new InetSocketAddress(8080), 0);
    server.createContext(
        "/health", exchange -> respond(exchange, "text/plain; charset=utf-8", "ok"));
    server.createContext(
        "/3/movie/916224",
        exchange -> respond(exchange, "application/json; charset=utf-8", MOVIE_916224_RESPONSE));
    server.createContext(
        "/3/movie/372058",
        exchange -> respond(exchange, "application/json; charset=utf-8", MOVIE_372058_RESPONSE));
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
