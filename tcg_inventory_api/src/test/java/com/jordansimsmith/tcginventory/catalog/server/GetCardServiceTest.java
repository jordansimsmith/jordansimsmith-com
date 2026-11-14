package com.jordansimsmith.tcginventory.catalog.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jordansimsmith.service.BadRequestException;
import com.jordansimsmith.service.NotFoundException;
import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.service.ServiceUnavailableException;
import com.jordansimsmith.tcginventory.catalog.client.GetCardRequest;
import com.jordansimsmith.tcginventory.games.client.FakeGamesService;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class GetCardServiceTest {
  private static final String CARD_ID = "4eaac4fd-95f5-4f38-b593-0101e79a20f9";

  private HttpServer server;
  private URI baseUri;
  private int status = 200;
  private String body;
  private String requestMethod;
  private String requestPath;
  private String userAgent;
  private String accept;
  private GetCardService service;

  @BeforeEach
  void setUp() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/cards", this::handleRequest);
    server.start();
    baseUri = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    var magic =
        new MagicTheGatheringPrintingLookup(
            baseUri, HttpClient.newHttpClient(), new ObjectMapper());
    var pokemon = new PokemonPrintingLookup(null, null, null, null, null, null);
    service = new GetCardService(new FakeGamesService(), magic, pokemon);
    body =
        """
        {"id":"%s","name":"Lightning Bolt","lang":"en","set":"lea","set_name":"Limited Edition Alpha","collector_number":"161","finishes":["nonfoil","foil"],"games":["paper"],"image_uris":{"small":"small.jpg","large":"large.jpg"},"card_faces":[{"image_uris":{"normal":"face-normal.jpg","large":"face-large.jpg"}}]}
        """
            .formatted(CARD_ID);
  }

  @AfterEach
  void tearDown() {
    server.stop(0);
  }

  @Test
  void getCardShouldReturnCanonicalMagicDetailsAndImageFallback() throws Exception {
    // arrange
    var request = new GetCardRequest("mtg", CARD_ID);

    // act
    var response = service.getCard(new RequestContext("alice"), request);

    // assert
    assertThat(response.card().externalId()).isEqualTo(CARD_ID);
    assertThat(response.card().availableFinishes()).containsExactly("normal", "foil");
    assertThat(response.card().imageUrls().small()).isEqualTo("small.jpg");
    assertThat(response.card().imageUrls().normal()).isEqualTo("face-normal.jpg");
    assertThat(requestMethod).isEqualTo("GET");
    assertThat(requestPath).isEqualTo("/cards/" + CARD_ID);
    assertThat(userAgent).startsWith("TcgInventory/");
    assertThat(accept).isEqualTo("application/json");
  }

  @Test
  void getCardShouldRejectBlankInputsBeforeProviderAccess() {
    // arrange
    var blankGame = new GetCardRequest(" ", CARD_ID);
    var blankId = new GetCardRequest("mtg", " ");
    var missing = new GetCardRequest(null, null);

    // act / assert
    assertThatThrownBy(() -> service.getCard(new RequestContext("alice"), blankGame))
        .isInstanceOf(BadRequestException.class);
    assertThatThrownBy(() -> service.getCard(new RequestContext("alice"), blankId))
        .isInstanceOf(BadRequestException.class);
    assertThatThrownBy(() -> service.getCard(new RequestContext("alice"), missing))
        .isInstanceOf(BadRequestException.class);
  }

  @Test
  void getCardShouldPreserveUnknownGameAndRejectMalformedMagicId() {
    // arrange
    var unknownGame = new GetCardRequest("unknown", "opaque");
    var malformedId = new GetCardRequest("mtg", "bad-id");

    // act / assert
    assertThatThrownBy(() -> service.getCard(new RequestContext("alice"), unknownGame))
        .isInstanceOf(NotFoundException.class)
        .hasMessage("unsupported game: unknown");
    assertThatThrownBy(() -> service.getCard(new RequestContext("alice"), malformedId))
        .isInstanceOf(BadRequestException.class);
  }

  @Test
  void getCardShouldReturnNotFoundForMissingAndNonEnglishCards() throws Exception {
    // arrange
    status = 404;
    var missing = new GetCardRequest("mtg", CARD_ID);
    status = 200;
    body = body.replace("\"lang\":\"en\"", "\"lang\":\"ja\"");
    var nonEnglish = new GetCardRequest("mtg", CARD_ID);

    // act / assert
    status = 404;
    assertThatThrownBy(() -> service.getCard(new RequestContext("alice"), missing))
        .isInstanceOf(NotFoundException.class);
    status = 200;
    assertThatThrownBy(() -> service.getCard(new RequestContext("alice"), nonEnglish))
        .isInstanceOf(NotFoundException.class);
  }

  @Test
  void getCardShouldReturnUnavailableForProviderFailureAndInconsistentResponse() {
    // arrange
    var request = new GetCardRequest("mtg", CARD_ID);

    // act / assert
    status = 429;
    assertThatThrownBy(() -> service.getCard(new RequestContext("alice"), request))
        .isInstanceOf(ServiceUnavailableException.class)
        .hasMessage("catalog is temporarily unavailable")
        .hasCauseInstanceOf(ServiceUnavailableException.class);
    status = 200;
    body = body.replace(CARD_ID, "c83275d7-0d4e-4e25-b8b8-433e9d2a9290");
    assertThatThrownBy(() -> service.getCard(new RequestContext("alice"), request))
        .isInstanceOf(ServiceUnavailableException.class)
        .hasMessage("catalog is temporarily unavailable")
        .hasCauseInstanceOf(ServiceUnavailableException.class);
  }

  private void handleRequest(HttpExchange exchange) throws IOException {
    requestMethod = exchange.getRequestMethod();
    requestPath = exchange.getRequestURI().getPath();
    userAgent = exchange.getRequestHeaders().getFirst("User-Agent");
    accept = exchange.getRequestHeaders().getFirst("Accept");
    var responseBody = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", "application/json");
    exchange.sendResponseHeaders(status, responseBody.length);
    exchange.getResponseBody().write(responseBody);
    exchange.close();
  }
}
