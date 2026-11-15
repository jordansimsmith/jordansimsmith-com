package com.jordansimsmith.tcginventory.catalog.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jordansimsmith.service.BadRequestException;
import com.jordansimsmith.service.NotFoundException;
import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.service.ServiceUnavailableException;
import com.jordansimsmith.tcginventory.catalog.client.FindCardAlternativesRequest;
import com.jordansimsmith.tcginventory.games.client.FakeGamesService;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class FindCardAlternativesServiceTest {
  private static final String CARD_ID = id(1);

  private final ObjectMapper objectMapper = new ObjectMapper();
  private final List<String> requestedUris = new ArrayList<>();
  private HttpServer server;
  private URI baseUri;
  private String searchBody;
  private String printsSearchUri;
  private List<String> anchorFinishes;
  private int cardStatus;
  private int searchStatus;
  private FindCardAlternativesService service;

  @BeforeEach
  void setUp() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/cards", this::handleRequest);
    server.start();
    baseUri = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    printsSearchUri = baseUri + "/cards/search?order=released&q=oracleid%3Aabc&unique=prints";
    anchorFinishes = List.of("nonfoil", "foil");
    var magic =
        new MagicTheGatheringPrintingLookup(baseUri, HttpClient.newHttpClient(), objectMapper);
    service = new FindCardAlternativesService(new FakeGamesService(), magic, null);
    searchBody = searchPage(25, false, true);
    cardStatus = 200;
    searchStatus = 200;
  }

  @AfterEach
  void tearDown() {
    server.stop(0);
  }

  @Test
  void findCardAlternativesShouldPlaceAnchorFirstAndPageDistinctPrintings() throws Exception {
    // arrange
    var context = new RequestContext("alice");
    var firstRequest = new FindCardAlternativesRequest("mtg", CARD_ID, "normal", null, null);

    // act
    var first = service.findCardAlternatives(context, firstRequest);
    var second =
        service.findCardAlternatives(
            context,
            new FindCardAlternativesRequest(
                "mtg", CARD_ID, "normal", null, first.nextContinuation()));

    // assert
    assertThat(first.cards()).hasSize(20);
    assertThat(first.cards().getFirst().externalId()).isEqualTo(CARD_ID);
    assertThat(first.cards()).extracting(card -> card.externalId()).doesNotHaveDuplicates();
    assertThat(first.nextContinuation()).isNotNull();
    assertThat(second.cards()).hasSize(5);
    assertThat(second.cards()).extracting(card -> card.externalId()).doesNotContain(CARD_ID);
    assertThat(second.nextContinuation()).isNull();
    assertThat(requestedUris.getFirst()).isEqualTo("/cards/" + CARD_ID);
    assertThat(requestedUris.get(1)).contains("/cards/search?");
    assertThat(requestedUris.get(1)).contains("page=1");
  }

  @Test
  void findCardAlternativesShouldContinueProviderPagingWhenAnchorFillsLimitOne() throws Exception {
    // arrange
    var context = new RequestContext("alice");
    var request = new FindCardAlternativesRequest("mtg", CARD_ID, "normal", 1, null);

    // act
    var first = service.findCardAlternatives(context, request);
    var second =
        service.findCardAlternatives(
            context,
            new FindCardAlternativesRequest("mtg", CARD_ID, "normal", 1, first.nextContinuation()));

    // assert
    assertThat(first.cards()).extracting(card -> card.externalId()).containsExactly(CARD_ID);
    assertThat(first.nextContinuation()).isNotNull();
    assertThat(second.cards()).extracting(card -> card.externalId()).containsExactly(id(2));
  }

  @Test
  void findCardAlternativesShouldOmitIneligibleAnchorButUseItsPrintings() throws Exception {
    // arrange
    searchBody = searchPage(25, false, false);
    anchorFinishes = List.of("nonfoil");

    // act
    var response =
        service.findCardAlternatives(
            new RequestContext("alice"),
            new FindCardAlternativesRequest("mtg", CARD_ID, "foil", 3, null));

    // assert
    assertThat(response.cards()).hasSize(3);
    assertThat(response.cards()).extracting(card -> card.externalId()).doesNotContain(CARD_ID);
    assertThat(response.cards().getFirst().availableFinishes()).contains("foil");
  }

  @Test
  void findCardAlternativesShouldRejectInvalidInputAndPreserveMissingAnchor() {
    // arrange
    var context = new RequestContext("alice");

    // act / assert
    assertThatThrownBy(
            () ->
                service.findCardAlternatives(
                    context,
                    new FindCardAlternativesRequest("mtg", "bad-id", "normal", null, null)))
        .isInstanceOf(BadRequestException.class);
    assertThatThrownBy(
            () ->
                service.findCardAlternatives(
                    context, new FindCardAlternativesRequest("mtg", CARD_ID, "normal", 0, null)))
        .isInstanceOf(BadRequestException.class);
    assertThatThrownBy(
            () ->
                service.findCardAlternatives(
                    context,
                    new FindCardAlternativesRequest("mtg", CARD_ID, "normal", null, "invalid!")))
        .isInstanceOf(BadRequestException.class);
    cardStatus = 404;
    assertThatThrownBy(
            () ->
                service.findCardAlternatives(
                    context, new FindCardAlternativesRequest("mtg", CARD_ID, "normal", null, null)))
        .isInstanceOf(NotFoundException.class);
  }

  @Test
  void findCardAlternativesShouldRejectInvalidPrintingSearchUri() {
    // arrange
    printsSearchUri = "https://example.com/cards/search?q=oracleid%3Aabc";

    // act / assert
    assertThatThrownBy(
            () ->
                service.findCardAlternatives(
                    new RequestContext("alice"),
                    new FindCardAlternativesRequest("mtg", CARD_ID, "normal", null, null)))
        .isInstanceOf(ServiceUnavailableException.class);
  }

  @Test
  void findCardAlternativesShouldStopAfterProviderPageReadCap() throws Exception {
    // arrange
    searchBody = searchPage(175, true, false);
    var context = new RequestContext("alice");

    // act
    var response =
        service.findCardAlternatives(
            context, new FindCardAlternativesRequest("mtg", CARD_ID, "etched", 20, null));

    // assert
    assertThat(response.cards()).isEmpty();
    assertThat(response.nextContinuation()).isNotNull();
    assertThat(requestedUris.stream().filter(uri -> uri.startsWith("/cards/search?")).count())
        .isEqualTo(5);
  }

  private void handleRequest(HttpExchange exchange) throws IOException {
    requestedUris.add(exchange.getRequestURI().toString());
    var path = exchange.getRequestURI().getPath();
    var status = path.equals("/cards/search") ? searchStatus : cardStatus;
    var body = path.equals("/cards/search") ? searchBody : card(CARD_ID, "en", anchorFinishes);
    exchange.sendResponseHeaders(status, body.length());
    try (var output = exchange.getResponseBody()) {
      output.write(body.getBytes(StandardCharsets.UTF_8));
    }
  }

  private String searchPage(int count, boolean hasMore, boolean anchorEligible) throws IOException {
    var cards = new ArrayList<String>();
    for (int index = 1; index <= count; index++) {
      cards.add(
          card(
              id(index),
              "en",
              index == 1 && !anchorEligible ? List.of("foil") : List.of("nonfoil", "foil")));
    }
    if (hasMore) {
      for (int index = count + 1; index <= 175; index++) {
        cards.add(card(id(index), "en", List.of("foil")));
      }
    }
    return "{\"data\":[%s],\"has_more\":%s}".formatted(String.join(",", cards), hasMore);
  }

  private String card(String id, String language, List<String> finishes) throws IOException {
    return objectMapper.writeValueAsString(
        Map.of(
            "id",
            id,
            "name",
            "Lightning Bolt",
            "lang",
            language,
            "set",
            "lea",
            "set_name",
            "Limited Edition Alpha",
            "collector_number",
            "161",
            "finishes",
            finishes,
            "games",
            List.of("paper"),
            "prints_search_uri",
            printsSearchUri,
            "image_uris",
            Map.of("small", "small.jpg", "normal", "normal.jpg")));
  }

  private static String id(int suffix) {
    return "00000000-0000-0000-0000-%012d".formatted(suffix);
  }
}
