package com.jordansimsmith.subfootballtracker;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

public final class SubfootballWebsiteStubServer {
  private static final String REGISTER_HTML =
      """
      <!doctype html>
      <html lang="en-NZ">
        <head>
          <meta charset="utf-8" />
          <meta name="viewport" content="width=device-width, initial-scale=1" />
          <title>SUB Football - Register</title>
        </head>
        <body>
          <header>
            <nav>
              <a href="/">Home</a>
              <a href="/register">Register</a>
              <a href="/fixtures">Fixtures</a>
            </nav>
          </header>
          <main>
            <article class="page content-item">
              <h2>REGISTER</h2>
              <h3>Turf Leagues</h3>
              <p>Turf League registrations are now open.</p>
              <p><strong>Where:</strong> Auckland Grammar Turf, Normanby Rd, Mt Eden</p>
              <p><strong>Autumn Leagues:</strong> Start Tuesday 17th March 2026</p>
              <p><strong>Winter Leagues:</strong> Start Tuesday 2nd June 2026</p>
              <p><strong>Spring Leagues:</strong> Start Tuesday 18th August 2026</p>
            </article>
          </main>
          <footer>
            <p>SUB Football Auckland</p>
          </footer>
        </body>
      </html>
      """;

  private SubfootballWebsiteStubServer() {}

  public static void main(String[] args) throws Exception {
    var server = HttpServer.create(new InetSocketAddress(8080), 0);
    server.createContext(
        "/register",
        exchange -> {
          var body = REGISTER_HTML.getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
          exchange.sendResponseHeaders(200, body.length);
          try (var output = exchange.getResponseBody()) {
            output.write(body);
          }
        });
    server.start();
    Thread.currentThread().join();
  }
}
