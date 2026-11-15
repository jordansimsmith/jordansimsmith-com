package com.jordansimsmith.tcginventory.catalog.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jordansimsmith.service.BadRequestException;
import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.service.ServiceUnavailableException;
import com.jordansimsmith.tcginventory.catalog.client.FindCardsRequest;
import com.jordansimsmith.tcginventory.games.client.FakeGamesService;
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
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class FindCardsServiceTest {
  private final ObjectMapper objectMapper = new ObjectMapper();
  private final List<JsonNode> collectionRequests = new ArrayList<>();
  private HttpServer server;
  private FindCardsService service;
  private String collectionBody;
  private int secondStatus;
  private String secondBody;

  @BeforeEach
  void setUp() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/cards/collection", this::handleCollection);
    server.start();
    var baseUri = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    var magic =
        new MagicTheGatheringPrintingLookup(baseUri, HttpClient.newHttpClient(), objectMapper);
    service = new FindCardsService(new FakeGamesService(), magic, null);
    secondStatus = 200;
    secondBody = null;
    collectionBody = null;
  }

  @AfterEach
  void tearDown() {
    server.stop(0);
  }

  @Test
  void findCardsShouldAcceptOneHundredIdsAndChunkAtSeventyFive() throws Exception {
    // arrange
    var ids = IntStream.range(0, 100).mapToObj(index -> new UUID(0, index).toString()).toList();

    // act
    var cards =
        service.findCards(new RequestContext("alice"), new FindCardsRequest("mtg", ids)).cards();

    // assert
    assertThat(cards)
        .extracting(card -> card.externalId())
        .containsExactlyInAnyOrderElementsOf(ids);
    assertThat(collectionRequests)
        .extracting(request -> request.get("identifiers").size())
        .containsExactly(75, 25);
  }

  @Test
  void findCardsShouldValidateRequestBeforeCallingProviders() throws Exception {
    // arrange
    var context = new RequestContext("alice");

    // act / assert
    assertThat(service.findCards(context, new FindCardsRequest("mtg", List.of())).cards())
        .isEmpty();
    for (var invalid :
        List.of(
            new FindCardsRequest("mtg", Collections.nCopies(101, uuid(1))),
            new FindCardsRequest("mtg", Collections.singletonList(null)),
            new FindCardsRequest("mtg", List.of(" ")),
            new FindCardsRequest("mtg", null))) {
      assertThatThrownBy(() -> service.findCards(context, invalid))
          .isInstanceOf(BadRequestException.class);
    }
    assertThat(collectionRequests).isEmpty();
  }

  @Test
  void findCardsShouldReturnOnlyExactEligiblePrintingsOnce() throws Exception {
    // arrange
    var canonical = new UUID(0, 0xabcdef).toString();
    var upperCase = canonical.toUpperCase(Locale.ROOT);
    var context = new RequestContext("alice");
    var response =
        """
        {"data":[
          {"id":"%s","name":"Card","lang":"en","set":"lea","set_name":"Alpha","collector_number":"1","finishes":["nonfoil","foil"],"games":["paper"]},
          {"id":"%s","name":"Card","lang":"en","set":"lea","set_name":"Alpha","collector_number":"1","finishes":["foil"],"games":["paper"]},
          {"id":"%s","name":"Card","lang":"ja","set":"lea","set_name":"Alpha","collector_number":"2","finishes":["nonfoil"],"games":["paper"]},
          {"id":"%s","name":"Card","lang":"en","set":"lea","set_name":"Alpha","collector_number":"3","finishes":["glossy"],"games":["paper"]}
        ],"not_found":[{"id":"%s"}]}
        """
            .formatted(canonical, upperCase, uuid(2), uuid(3), uuid(4));
    collectionBody = response;

    // act
    var cards =
        service
            .findCards(
                context,
                new FindCardsRequest(
                    "mtg", List.of(canonical, upperCase, canonical, uuid(2), uuid(3), uuid(4))))
            .cards();

    // assert
    assertThat(cards)
        .extracting(card -> card.externalId())
        .containsExactlyInAnyOrder(canonical, upperCase);
    assertThat(cards)
        .filteredOn(card -> card.externalId().equals(canonical))
        .singleElement()
        .satisfies(card -> assertThat(card.availableFinishes()).containsExactly("normal", "foil"));
  }

  @Test
  void findCardsShouldFailTheWholeRequestWhenLaterChunkFailsOrIsIncomplete() {
    // arrange
    var ids = IntStream.range(0, 100).mapToObj(index -> new UUID(0, index).toString()).toList();
    secondStatus = 503;

    // act / assert
    assertThatThrownBy(
            () -> service.findCards(new RequestContext("alice"), new FindCardsRequest("mtg", ids)))
        .isInstanceOf(ServiceUnavailableException.class);
    assertThat(collectionRequests).hasSize(2);

    collectionRequests.clear();
    secondStatus = 200;
    secondBody = "{\"data\":[],\"not_found\":[]}";
    assertThatThrownBy(
            () -> service.findCards(new RequestContext("alice"), new FindCardsRequest("mtg", ids)))
        .isInstanceOf(ServiceUnavailableException.class);
    assertThat(collectionRequests).hasSize(2);
  }

  private void handleCollection(HttpExchange exchange) throws IOException {
    var request = objectMapper.readTree(exchange.getRequestBody());
    collectionRequests.add(request);
    var index = collectionRequests.size();
    var status = index == 2 ? secondStatus : 200;
    var body =
        collectionBody != null
            ? collectionBody
            : index == 2 && secondBody != null ? secondBody : collectionResponse(request);
    exchange.sendResponseHeaders(status, body.getBytes(StandardCharsets.UTF_8).length);
    try (var output = exchange.getResponseBody()) {
      output.write(body.getBytes(StandardCharsets.UTF_8));
    }
  }

  private String collectionResponse(JsonNode request) throws IOException {
    var data = objectMapper.createArrayNode();
    for (var identifier : request.get("identifiers")) {
      var card = objectMapper.createObjectNode();
      card.put("id", identifier.get("id").asText());
      card.put("name", "Card");
      card.put("lang", "en");
      card.put("set", "lea");
      card.put("set_name", "Limited Edition Alpha");
      card.put("collector_number", "1");
      card.set("finishes", objectMapper.valueToTree(List.of("nonfoil", "foil")));
      card.set("games", objectMapper.valueToTree(List.of("paper")));
      data.add(card);
    }
    return objectMapper.writeValueAsString(Map.of("data", data, "not_found", List.of()));
  }

  private static String uuid(int suffix) {
    return new UUID(0, suffix).toString();
  }
}
