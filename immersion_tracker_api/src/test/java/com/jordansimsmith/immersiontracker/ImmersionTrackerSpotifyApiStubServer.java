package com.jordansimsmith.immersiontracker;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

public final class ImmersionTrackerSpotifyApiStubServer {
  private static final String EPISODE_4QJERZMW8JFD30VOG0TJPK_RESPONSE =
      """
      {
        "id": "4qjerzMw8jfD30VOG0tjpK",
        "name": "No 1 紹介(しょうかい) Introduction",
        "duration_ms": 388000,
        "show": {
          "id": "6Nl8RDfPxsk4h4bfWe76Kg",
          "name": "The Miku Real Japanese Podcast | Japanese conversation | Japanese culture",
          "images": [
            {
              "url": "https://i.scdn.co/image/ab6765630000ba8a1234"
            }
          ]
        }
      }
      """;

  private static final String EPISODE_5TMVVWD9TOCAF2BETYYDWV_RESPONSE =
      """
      {
        "id": "5TmVVWd9TOCaF2bEtyYDwv",
        "name": "No 2 Listening Training",
        "duration_ms": 3015000,
        "show": {
          "id": "6Nl8RDfPxsk4h4bfWe76Kg",
          "name": "The Miku Real Japanese Podcast | Japanese conversation | Japanese culture",
          "images": [
            {
              "url": "https://i.scdn.co/image/ab6765630000ba8a1234"
            }
          ]
        }
      }
      """;

  private ImmersionTrackerSpotifyApiStubServer() {}

  public static void main(String[] args) throws Exception {
    var server = HttpServer.create(new InetSocketAddress(8080), 0);
    server.createContext(
        "/health", exchange -> respond(exchange, "text/plain; charset=utf-8", "ok"));
    server.createContext(
        "/v1/episodes/4qjerzMw8jfD30VOG0tjpK",
        exchange ->
            respond(
                exchange,
                "application/json; charset=utf-8",
                EPISODE_4QJERZMW8JFD30VOG0TJPK_RESPONSE));
    server.createContext(
        "/v1/episodes/5TmVVWd9TOCaF2bEtyYDwv",
        exchange ->
            respond(
                exchange,
                "application/json; charset=utf-8",
                EPISODE_5TMVVWD9TOCAF2BETYYDWV_RESPONSE));
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
