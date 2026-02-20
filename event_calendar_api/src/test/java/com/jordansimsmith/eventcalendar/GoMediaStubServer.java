package com.jordansimsmith.eventcalendar;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

public final class GoMediaStubServer {
  private static final String MAIN_PAGE_HTML =
      """
      <html>
        <body>
          <article class="new-tile new-tile-event">
            <h5>One NZ Warriors v Sea Eagles 2025 Season</h5>
            <p class="event-date">Friday 14 March</p>
            <p class="location">Go Media Stadium</p>
            <a class="new-tile-inner" href="/event/one-nz-warriors-v-sea-eagles-2025-season"></a>
            <div class="event-summary">
              <ul>
                <li>Box office opens at 2PM, Gates open at 5PM</li>
              </ul>
            </div>
          </article>
          <article class="new-tile new-tile-event">
            <h5>One NZ Warriors 2025 NRL Home Season</h5>
            <p class="event-date">March - September</p>
            <p class="location">Go Media Stadium</p>
            <a class="new-tile-inner" href="/event/one-nz-warriors-2025-nrl-home-season"></a>
          </article>
        </body>
      </html>
      """;

  private static final String SEASON_PAGE_HTML =
      """
      <html>
        <body>
          <h1>One NZ Warriors 2025 NRL Home Season</h1>
          <div class="season-games">
            <ul>
              <li>
                <a href="/event/one-nz-warriors-v-sea-eagles-2025-season">One NZ Warriors v Sea Eagles 2025 Season</a>
              </li>
            </ul>
          </div>
        </body>
      </html>
      """;

  private static final String SEA_EAGLES_EVENT_PAGE_HTML =
      """
      <html>
        <body>
          <h1 class="event-hero-carousel-heading">One NZ Warriors v Sea Eagles 2025 Season</h1>
          <span class="event-hero-carousel-detail">
            <svg width="40" height="40" viewBox="0 0 40 40" name="event-calendar"></svg>
            14 March 2025
          </span>
          <div class="event-summary">
            <ul>
              <li>Box Office opens at 2PM, Gates open at 5PM</li>
              <li>NRL Kick off at 8:00PM</li>
            </ul>
          </div>
        </body>
      </html>
      """;

  private GoMediaStubServer() {}

  public static void main(String[] args) throws Exception {
    var server = HttpServer.create(new InetSocketAddress(8080), 0);

    server.createContext(
        "/health", exchange -> respond(exchange, "text/plain; charset=utf-8", "ok"));
    server.createContext(
        "/our-venues/go-media-stadium",
        exchange -> respond(exchange, "text/html; charset=utf-8", MAIN_PAGE_HTML));
    server.createContext(
        "/event/one-nz-warriors-2025-nrl-home-season",
        exchange -> respond(exchange, "text/html; charset=utf-8", SEASON_PAGE_HTML));
    server.createContext(
        "/event/one-nz-warriors-v-sea-eagles-2025-season",
        exchange -> respond(exchange, "text/html; charset=utf-8", SEA_EAGLES_EVENT_PAGE_HTML));

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
