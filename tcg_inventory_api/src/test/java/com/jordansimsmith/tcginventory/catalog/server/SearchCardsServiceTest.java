package com.jordansimsmith.tcginventory.catalog.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jordansimsmith.service.BadRequestException;
import com.jordansimsmith.service.NotFoundException;
import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.service.ServiceUnavailableException;
import com.jordansimsmith.tcginventory.catalog.client.SearchCardsRequest;
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

public class SearchCardsServiceTest {
  private static final String CARD_ID = "00000000-0000-0000-0000-000000000001";

  private final ObjectMapper objectMapper = new ObjectMapper();
  private final List<String> requestedUris = new ArrayList<>();
  private HttpServer server;
  private String searchBody;
  private int status;
  private SearchCardsService service;

  @BeforeEach
  void setUp() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/cards", this::handleRequest);
    server.start();
    var baseUri = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    var magic = new ScryfallPrintingLookup(baseUri, HttpClient.newHttpClient(), objectMapper);
    service = new SearchCardsService(new FakeGamesService(), magic, null);
    searchBody = searchPage(searchCards(25), false);
    status = 200;
  }

  @AfterEach
  void tearDown() {
    server.stop(0);
  }

  @Test
  void searchCardsShouldReturnDistinctTwentyCardPagesWithOpaqueContinuation() throws Exception {
    // arrange
    var context = new RequestContext("alice");
    var request = new SearchCardsRequest("mtg", " Lightning Bolt ", "normal", null, null);

    // act
    var first = service.searchCards(context, request);
    var second =
        service.searchCards(
            context,
            new SearchCardsRequest(
                "mtg", "Lightning Bolt", "normal", null, first.nextContinuation()));

    // assert
    assertThat(first.cards()).hasSize(20);
    assertThat(first.cards().getFirst().externalId()).isEqualTo(id(1));
    assertThat(first.cards().getFirst().availableFinishes()).containsExactly("normal", "foil");
    assertThat(first.nextContinuation()).isNotNull();
    assertThat(second.cards()).hasSize(5);
    assertThat(second.nextContinuation()).isNull();
    assertThat(first.cards())
        .extracting(card -> card.externalId())
        .doesNotContainAnyElementsOf(
            second.cards().stream().map(card -> card.externalId()).toList());
    assertThat(requestedUris.getFirst()).contains("name%3A%22Lightning%20Bolt%22");
    var differentSearch =
        service.searchCards(
            context,
            new SearchCardsRequest(
                "mtg", "Different Card", "normal", null, first.nextContinuation()));
    assertThat(differentSearch.cards()).hasSize(5);
    assertThatThrownBy(
            () ->
                service.searchCards(
                    context,
                    new SearchCardsRequest("mtg", "Lightning Bolt", "normal", null, "bad!")))
        .isInstanceOf(BadRequestException.class)
        .hasMessage("continuation is invalid");
  }

  @Test
  void searchCardsShouldRespectRequestedLimit() throws Exception {
    // arrange / act
    var response =
        service.searchCards(
            new RequestContext("alice"),
            new SearchCardsRequest("mtg", "Lightning Bolt", "normal", 7, null));

    // assert
    assertThat(response.cards()).hasSize(7);
    assertThat(response.nextContinuation()).isNotNull();
  }

  @Test
  void searchCardsShouldEscapeQueryAsLiteralNamePhrase() throws Exception {
    // arrange
    var query = "Lightning\" OR name:*";

    // act
    service.searchCards(
        new RequestContext("alice"), new SearchCardsRequest("mtg", query, "normal", null, null));

    // assert
    assertThat(requestedUris.getFirst()).contains("name%3A%22Lightning%5C%22%20OR%20name%3A*%22");
  }

  @Test
  void searchCardsShouldRejectInvalidInputsBeforeCallingProvider() {
    // arrange
    var context = new RequestContext("alice");
    var zeroLimit = new SearchCardsRequest("mtg", "Bolt", "normal", 0, null);
    var oversizedLimit = new SearchCardsRequest("mtg", "Bolt", "normal", 101, null);
    var unsupportedFinish = new SearchCardsRequest("mtg", "Bolt", "reverse_holofoil", null, null);
    var shortQuery = new SearchCardsRequest("mtg", "x", "normal", null, null);

    // act / assert
    assertThatThrownBy(() -> service.searchCards(context, zeroLimit))
        .isInstanceOf(BadRequestException.class)
        .hasMessage("limit must be between 1 and 100");
    assertThatThrownBy(() -> service.searchCards(context, oversizedLimit))
        .isInstanceOf(BadRequestException.class)
        .hasMessage("limit must be between 1 and 100");
    assertThatThrownBy(() -> service.searchCards(context, unsupportedFinish))
        .isInstanceOf(BadRequestException.class);
    assertThatThrownBy(() -> service.searchCards(context, shortQuery))
        .isInstanceOf(BadRequestException.class);
    assertThat(requestedUris).isEmpty();
  }

  @Test
  void searchCardsShouldReturnUnavailableForProviderFailures() {
    // arrange
    status = 503;

    // act / assert
    assertThatThrownBy(
            () ->
                service.searchCards(
                    new RequestContext("alice"),
                    new SearchCardsRequest("mtg", "Lightning Bolt", "normal", null, null)))
        .isInstanceOf(ServiceUnavailableException.class)
        .hasMessage("catalog is temporarily unavailable")
        .hasRootCauseMessage("Scryfall returned HTTP 503");
  }

  @Test
  void searchCardsShouldReturnEmptyWhenProviderReportsNoMatches() throws Exception {
    // arrange
    status = 404;

    // act
    var response =
        service.searchCards(
            new RequestContext("alice"),
            new SearchCardsRequest("mtg", "No Such Card", "normal", null, null));

    // assert
    assertThat(response.cards()).isEmpty();
    assertThat(response.nextContinuation()).isNull();
  }

  @Test
  void searchCardsShouldPreserveUnknownGameError() {
    // arrange
    var request = new SearchCardsRequest("unknown", "Some Card", "normal", null, null);

    // act / assert
    assertThatThrownBy(() -> service.searchCards(new RequestContext("alice"), request))
        .isInstanceOf(NotFoundException.class);
    assertThat(requestedUris).isEmpty();
  }

  private void handleRequest(HttpExchange exchange) throws IOException {
    requestedUris.add(exchange.getRequestURI().toString());
    var responseStatus = status;
    var responseBody =
        "/cards/search".equals(exchange.getRequestURI().getPath())
            ? searchBody
            : card(CARD_ID, "en", List.of("nonfoil", "foil"), List.of("paper"));
    var body = responseBody.getBytes(StandardCharsets.UTF_8);
    exchange.sendResponseHeaders(responseStatus, body.length);
    try (var output = exchange.getResponseBody()) {
      output.write(body);
    }
  }

  private String searchPage(List<String> cards, boolean hasMore) throws IOException {
    var cardData = cards.stream().map(this::readCard).toList();
    return objectMapper.writeValueAsString(Map.of("data", cardData, "has_more", hasMore));
  }

  private Object readCard(String card) {
    try {
      return objectMapper.readTree(card);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  private List<String> searchCards(int count) throws IOException {
    var cards = new ArrayList<String>();
    for (int index = 1; index <= count; index++) {
      cards.add(card(id(index), "en", List.of("nonfoil", "foil"), List.of("paper")));
    }
    cards.add(card(id(1), "ja", List.of("nonfoil", "foil"), List.of("paper")));
    cards.add(card(id(40), "en", List.of("foil"), List.of("paper")));
    cards.add(card(id(41), "en", List.of("nonfoil", "foil"), List.of("mtgo")));
    return cards;
  }

  private String card(String id, String language, List<String> finishes, List<String> games)
      throws IOException {
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
            games,
            "image_uris",
            Map.of("small", "small.jpg", "normal", "normal.jpg")));
  }

  private String id(int suffix) {
    return "00000000-0000-0000-0000-%012d".formatted(suffix);
  }
}
