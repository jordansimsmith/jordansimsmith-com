package com.jordansimsmith.eventcalendar;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

public final class MeetupStubServer {
  private static final Pattern OPERATION_NAME_PATTERN =
      Pattern.compile("\"operationName\"\\s*:\\s*\"([^\"]+)\"");
  private static final Pattern URLNAME_PATTERN =
      Pattern.compile("\"urlname\"\\s*:\\s*\"([^\"]+)\"");
  private static final String EMPTY_EVENTS_RESPONSE =
      """
      {
        "data": {
          "groupByUrlname": {
            "events": {
              "edges": []
            }
          }
        }
      }
      """;

  private MeetupStubServer() {}

  public static void main(String[] args) throws Exception {
    var server = HttpServer.create(new InetSocketAddress(8080), 0);

    server.createContext(
        "/health", exchange -> respond(exchange, "text/plain; charset=utf-8", "ok"));

    server.createContext(
        "/gql2",
        exchange -> {
          var requestBody =
              new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
          var operationName = extractValue(OPERATION_NAME_PATTERN, requestBody);
          var urlname = normalizeUrlname(extractValue(URLNAME_PATTERN, requestBody));
          var responseBody =
              switch (operationName) {
                case "getUpcomingGroupEvents" -> upcomingEventsResponse(urlname);
                case "getPastGroupEvents" -> pastEventsResponse(urlname);
                default -> EMPTY_EVENTS_RESPONSE;
              };
          respond(exchange, "application/json; charset=utf-8", responseBody);
        });

    server.start();
    Thread.currentThread().join();
  }

  private static String extractValue(Pattern pattern, String body) {
    var matcher = pattern.matcher(body);
    return matcher.find() ? matcher.group(1) : "";
  }

  private static String normalizeUrlname(String urlname) {
    return urlname.isBlank() ? "test-group" : urlname;
  }

  private static String upcomingEventsResponse(String urlname) {
    return """
    {
      "data": {
        "groupByUrlname": {
          "events": {
            "edges": [
              {
                "node": {
                  "id": "310482719",
                  "title": "Test Event",
                  "eventUrl": "https://www.meetup.com/%s/events/310482719/",
                  "dateTime": "2025-11-22T15:00:00+13:00",
                  "venue": {
                    "name": "Test Venue",
                    "address": "123 Test St",
                    "city": "Auckland"
                  }
                }
              }
            ]
          }
        }
      }
    }
    """
        .formatted(urlname);
  }

  private static String pastEventsResponse(String urlname) {
    return """
    {
      "data": {
        "groupByUrlname": {
          "events": {
            "edges": [
              {
                "node": {
                  "id": "308779907",
                  "title": "Past Event",
                  "eventUrl": "https://www.meetup.com/%s/events/308779907/",
                  "dateTime": "2025-09-27T15:00:00+12:00",
                  "venue": {
                    "name": "Past Venue",
                    "address": "456 Past Ave",
                    "city": "Wellington"
                  }
                }
              }
            ]
          }
        }
      }
    }
    """
        .formatted(urlname);
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
