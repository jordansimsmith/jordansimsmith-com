package com.jordansimsmith.footballcalendar;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

public final class FootballFixStubServer {
  private static final String FIXTURES_HTML =
      """
      <html>
        <body>
          <table class="FTable">
            <tr class="FHeader">
              <td colspan="5">Wednesday 25 Nov 2026</td>
            </tr>
            <tr class="FRow FBand">
              <td class="FDate">6:55pm</td>
              <td class="FPlayingArea">Field 2<br /></td>
              <td class="FHomeTeam"><a href="#">Ladzio</a></td>
              <td class="FScore"><div><nobr data-fixture-id="167155">vs</nobr></div></td>
              <td class="FAwayTeam"><a href="#">Flamingoes</a></td>
            </tr>
          </table>
        </body>
      </html>
      """;

  private FootballFixStubServer() {}

  public static void main(String[] args) throws Exception {
    var server = HttpServer.create(new InetSocketAddress(8080), 0);
    server.createContext(
        "/health", exchange -> respond(exchange, "text/plain; charset=utf-8", "ok"));
    server.createContext(
        "/Leagues/Fixtures",
        exchange -> {
          if (!exchange
              .getRequestURI()
              .getQuery()
              .equals("SportId=0&VenueId=9&LeagueId=133&SeasonId=96&DivisionId=6562")) {
            exchange.sendResponseHeaders(404, -1);
            return;
          }
          respond(exchange, "text/html; charset=utf-8", FIXTURES_HTML);
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
