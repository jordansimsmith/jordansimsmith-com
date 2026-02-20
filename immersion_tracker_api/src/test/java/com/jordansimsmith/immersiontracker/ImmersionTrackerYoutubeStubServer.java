package com.jordansimsmith.immersiontracker;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

public final class ImmersionTrackerYoutubeStubServer {
  private static final String OFFICIALPSY_CHANNEL_ID = "UCffDXn7ycAzwL2LDlbyWOTw";
  private static final String LUIS_FONSI_CHANNEL_ID = "UCLp8RBhQHu9wSsq62j_Md6A";

  private static final String VIDEO_9BZKP7Q19F0_RESPONSE =
      """
      {
        "items": [
          {
            "id": "9bZkp7q19f0",
            "snippet": {
              "title": "PSY - GANGNAM STYLE(강남스타일) M/V",
              "channelId": "UCffDXn7ycAzwL2LDlbyWOTw",
              "channelTitle": "officialpsy"
            },
            "contentDetails": {
              "duration": "PT4M12S"
            }
          }
        ]
      }
      """;

  private static final String VIDEO_KJQP7KIW5FK_RESPONSE =
      """
      {
        "items": [
          {
            "id": "kJQP7kiw5Fk",
            "snippet": {
              "title": "Luis Fonsi - Despacito ft. Daddy Yankee",
              "channelId": "UCLp8RBhQHu9wSsq62j_Md6A",
              "channelTitle": "LuisFonsiVEVO"
            },
            "contentDetails": {
              "duration": "PT3M46S"
            }
          }
        ]
      }
      """;

  private static final String CHANNEL_OFFICIALPSY_RESPONSE =
      """
      {
        "items": [
          {
            "id": "UCffDXn7ycAzwL2LDlbyWOTw",
            "snippet": {
              "title": "officialpsy",
              "thumbnails": {
                "high": {
                  "url": "https://images.example.com/youtube/officialpsy.jpg"
                }
              }
            }
          }
        ]
      }
      """;

  private static final String CHANNEL_LUIS_FONSI_RESPONSE =
      """
      {
        "items": [
          {
            "id": "UCLp8RBhQHu9wSsq62j_Md6A",
            "snippet": {
              "title": "LuisFonsiVEVO",
              "thumbnails": {
                "high": {
                  "url": "https://images.example.com/youtube/luis-fonsi-vevo.jpg"
                }
              }
            }
          }
        ]
      }
      """;

  private ImmersionTrackerYoutubeStubServer() {}

  public static void main(String[] args) throws Exception {
    var server = HttpServer.create(new InetSocketAddress(8080), 0);
    server.createContext(
        "/health", exchange -> respond(exchange, "text/plain; charset=utf-8", "ok"));
    server.createContext(
        "/youtube/v3/videos",
        exchange -> {
          var query = exchange.getRequestURI().getQuery();
          var responseBody =
              query != null && query.contains("id=9bZkp7q19f0")
                  ? VIDEO_9BZKP7Q19F0_RESPONSE
                  : VIDEO_KJQP7KIW5FK_RESPONSE;
          respond(exchange, "application/json; charset=utf-8", responseBody);
        });
    server.createContext(
        "/youtube/v3/channels",
        exchange -> {
          var query = exchange.getRequestURI().getQuery();
          var responseBody =
              query != null && query.contains("id=" + OFFICIALPSY_CHANNEL_ID)
                  ? CHANNEL_OFFICIALPSY_RESPONSE
                  : CHANNEL_LUIS_FONSI_RESPONSE;
          respond(exchange, "application/json; charset=utf-8", responseBody);
        });
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
