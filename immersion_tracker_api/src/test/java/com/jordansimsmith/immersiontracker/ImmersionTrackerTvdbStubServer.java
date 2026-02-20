package com.jordansimsmith.immersiontracker;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

public final class ImmersionTrackerTvdbStubServer {
  private static final String LOGIN_RESPONSE =
      """
      {
        "status": "success",
        "data": {
          "token": "mock-tvdb-token"
        }
      }
      """;

  private static final String SERIES_278157_RESPONSE =
      """
      {
        "status": "success",
        "data": {
          "name": "Free!",
          "image": "https://images.example.com/tvdb/free.jpg",
          "averageRuntime": 24
        }
      }
      """;

  private static final String SERIES_270065_RESPONSE =
      """
      {
        "status": "success",
        "data": {
          "name": "ハイキュー!!",
          "image": "https://images.example.com/tvdb/haikyuu.jpg",
          "averageRuntime": 24
        }
      }
      """;

  private static final String MOVIE_331904_RESPONSE =
      """
      {
        "status": "success",
        "data": {
          "name": "すずめの戸締まり",
          "image": "https://images.example.com/tvdb/suzume.jpg",
          "runtime": 122
        }
      }
      """;

  private static final String MOVIE_197_RESPONSE =
      """
      {
        "status": "success",
        "data": {
          "name": "君の名は。",
          "image": "https://images.example.com/tvdb/your-name.jpg",
          "runtime": 106
        }
      }
      """;

  private ImmersionTrackerTvdbStubServer() {}

  public static void main(String[] args) throws Exception {
    var server = HttpServer.create(new InetSocketAddress(8080), 0);
    server.createContext(
        "/health", exchange -> respond(exchange, "text/plain; charset=utf-8", "ok"));
    server.createContext(
        "/v4/login",
        exchange -> respond(exchange, "application/json; charset=utf-8", LOGIN_RESPONSE));
    server.createContext(
        "/v4/series/278157",
        exchange -> respond(exchange, "application/json; charset=utf-8", SERIES_278157_RESPONSE));
    server.createContext(
        "/v4/series/270065",
        exchange -> respond(exchange, "application/json; charset=utf-8", SERIES_270065_RESPONSE));
    server.createContext(
        "/v4/movies/331904",
        exchange -> respond(exchange, "application/json; charset=utf-8", MOVIE_331904_RESPONSE));
    server.createContext(
        "/v4/movies/197",
        exchange -> respond(exchange, "application/json; charset=utf-8", MOVIE_197_RESPONSE));
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
