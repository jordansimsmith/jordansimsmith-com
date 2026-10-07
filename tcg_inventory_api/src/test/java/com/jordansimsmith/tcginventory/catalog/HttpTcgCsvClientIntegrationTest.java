package com.jordansimsmith.tcginventory.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class HttpTcgCsvClientIntegrationTest {
  private final List<String> paths = Collections.synchronizedList(new ArrayList<>());
  private final List<String> userAgents = Collections.synchronizedList(new ArrayList<>());
  private HttpServer server;
  private HttpTcgCsvClient client;
  private AtomicInteger pacedRequests;
  private volatile int nextStatus;
  private volatile String nextBody;

  @BeforeEach
  void setUp() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/", this::handleRequest);
    server.start();
    var baseUri = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    pacedRequests = new AtomicInteger();
    client =
        new HttpTcgCsvClient(
            baseUri,
            HttpClient.newHttpClient(),
            new ObjectMapper(),
            pacedRequests::incrementAndGet);
    nextStatus = 200;
    nextBody = null;
  }

  @AfterEach
  void tearDown() {
    server.stop(0);
  }

  @Test
  void requestsShouldUseTheTcgcsvPathsUserAgentAndPacer() throws Exception {
    // arrange
    nextBody = "2026-10-07T20:06:09+0000";

    // act
    var marker = client.getUpdateMarker();
    nextBody =
        collection(
            "groupId", 3, "categoryId", 3, "name", "Silver Tempest", "abbreviation", "SWSH12");
    var groups = client.findGroups(3);
    nextBody =
        collection(
            "productId",
            10,
            "categoryId",
            3,
            "groupId",
            3170,
            "name",
            "Candice",
            "extendedData",
            List.of());
    var products = client.findProducts(3, 3170);
    nextBody = collection("productId", 10, "subTypeName", "Normal", "marketPrice", null);
    var prices = client.findPrices(3, 3170);

    // assert
    assertThat(marker.sourceMarker()).isEqualTo("2026-10-07T20:06:09+0000");
    assertThat(marker.updatedAt().toString()).isEqualTo("2026-10-07T20:06:09Z");
    assertThat(groups).singleElement().extracting(TcgCsvClient.Group::groupId).isEqualTo(3);
    assertThat(products).singleElement().extracting(TcgCsvClient.Product::productId).isEqualTo(10);
    assertThat(prices)
        .singleElement()
        .extracting(TcgCsvClient.Price::subTypeName)
        .isEqualTo("Normal");
    assertThat(paths)
        .containsExactly(
            "/last-updated.txt",
            "/tcgplayer/3/groups",
            "/tcgplayer/3/3170/products",
            "/tcgplayer/3/3170/prices");
    assertThat(userAgents)
        .allMatch(
            userAgent ->
                userAgent.equals("TcgInventory/1.0 (https://tcg-inventory.jordansimsmith.com)"));
    assertThat(pacedRequests).hasValue(4);
  }

  @Test
  void collectionShouldFailWhenSourceReportsFailureOrAnIncorrectCount() {
    // arrange
    nextBody = "{\"success\":false,\"errors\":[\"bad\"],\"totalItems\":0,\"results\":[]}";

    // act / assert
    assertThatThrownBy(() -> client.findGroups(3))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("unsuccessful collection");

    // arrange
    nextBody = "{\"success\":true,\"errors\":[],\"totalItems\":2,\"results\":[]}";

    // act / assert
    assertThatThrownBy(() -> client.findGroups(3))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("count does not match");
  }

  @Test
  void requestShouldFailOnNonSuccessStatusAndMalformedUpdateMarker() {
    // arrange
    nextStatus = 429;

    // act / assert
    assertThatThrownBy(() -> client.findGroups(3))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("status code 429");

    // arrange
    nextStatus = 200;
    nextBody = "yesterday";

    // act / assert
    assertThatThrownBy(() -> client.getUpdateMarker())
        .isInstanceOf(IOException.class)
        .hasMessageContaining("not an offset timestamp");
  }

  @Test
  void collectionShouldRejectMalformedJsonAndPreserveRowsWithNullPrices() throws Exception {
    // arrange
    nextBody = collection("productId", 10, "subTypeName", "Holofoil", "marketPrice", null);

    // act
    var prices = client.findPrices(3, 3170);

    // assert
    assertThat(prices)
        .singleElement()
        .extracting(TcgCsvClient.Price::subTypeName)
        .isEqualTo("Holofoil");

    // arrange
    nextBody = "{not-json";

    // act / assert
    assertThatThrownBy(() -> client.findGroups(3)).isInstanceOf(IOException.class);
  }

  private void handleRequest(HttpExchange exchange) throws IOException {
    paths.add(exchange.getRequestURI().getPath());
    userAgents.add(exchange.getRequestHeaders().getFirst("User-Agent"));
    var body = nextBody == null ? "{}" : nextBody;
    var bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.sendResponseHeaders(nextStatus, bytes.length);
    try (var output = exchange.getResponseBody()) {
      output.write(bytes);
    }
  }

  private static String collection(Object... values) throws Exception {
    var fields = new ObjectMapper().createObjectNode();
    fields.put("success", true);
    fields.putArray("errors");
    fields.put("totalItems", 1);
    var item = fields.putArray("results").addObject();
    for (int index = 0; index < values.length; index += 2) {
      var key = (String) values[index];
      var value = values[index + 1];
      if (value instanceof Integer integer) {
        item.put(key, integer);
      } else if (value instanceof String string) {
        item.put(key, string);
      } else {
        item.set(key, new ObjectMapper().valueToTree(value));
      }
    }
    return new ObjectMapper().writeValueAsString(fields);
  }
}
