package com.jordansimsmith.llm;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

public final class OpenAiStubServer {
  private OpenAiStubServer() {}

  public static void main(String[] args) throws Exception {
    var content = System.getenv().getOrDefault("OPENAI_STUB_RESPONSE_CONTENT", "{}");
    var completionBody =
        """
        {
          "id": "chatcmpl-stub",
          "choices": [
            {
              "index": 0,
              "message": {
                "role": "assistant",
                "content": "%s"
              }
            }
          ],
          "usage": {
            "prompt_tokens": 1,
            "completion_tokens": 1
          }
        }
        """
            .formatted(escapeJson(content));

    var server = HttpServer.create(new InetSocketAddress(8080), 0);
    server.createContext(
        "/health",
        exchange -> {
          respond(exchange, 200, "text/plain; charset=utf-8", "ok");
        });
    server.createContext(
        "/",
        exchange -> {
          var path = exchange.getRequestURI().getPath();
          if (path.equals("/v1/chat/completions")) {
            respond(exchange, 200, "application/json; charset=utf-8", completionBody);
            return;
          }

          respond(exchange, 404, "text/plain; charset=utf-8", "not found");
        });
    server.start();
    Thread.currentThread().join();
  }

  private static String escapeJson(String value) {
    return value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t");
  }

  private static void respond(
      HttpExchange exchange, int statusCode, String contentType, String body) throws IOException {
    var bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", contentType);
    exchange.sendResponseHeaders(statusCode, bytes.length);
    try (var output = exchange.getResponseBody()) {
      output.write(bytes);
    }
  }
}
