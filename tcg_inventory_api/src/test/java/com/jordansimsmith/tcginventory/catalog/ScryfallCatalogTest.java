package com.jordansimsmith.tcginventory.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jordansimsmith.http.HttpResponseFactory;
import com.jordansimsmith.http.RequestContextFactory;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class ScryfallCatalogTest {
  private static final String AUTH_HEADER = "Basic am9yZGFuOnBhc3N3b3Jk";
  private static final String CARD_ID = "4eaac4fd-95f5-4f38-b593-0101e79a20f9";
  private static final String ALT_ID = "c83275d7-0d4e-4e25-b8b8-433e9d2a9290";
  private static final String TOKEN_ID = "1c1d32fc-7cc2-4d4e-93ac-f1ce83219819";
  private static final String ORACLE_ID = "4457ed35-7c10-48c8-9776-456485fdf070";

  private final ObjectMapper objectMapper = new ObjectMapper();
  private final List<URI> requests = new ArrayList<>();
  private final List<String> requestMethods = new ArrayList<>();
  private final List<JsonNode> collectionRequests = new ArrayList<>();
  private HttpServer server;
  private URI baseUri;
  private int nextStatus;
  private String nextBody;
  private String searchBody;
  private String collectionBody;
  private long nextDelayMillis;
  private ScryfallCatalog catalog;
  private HttpResponseFactory responseFactory;

  @BeforeEach
  void setUp() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/cards", this::handleRequest);
    server.start();
    baseUri = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    nextStatus = 200;
    nextBody = null;
    searchBody = null;
    collectionBody = null;
    nextDelayMillis = 0;
    catalog = new ScryfallCatalog(baseUri, HttpClient.newHttpClient(), objectMapper, () -> {});
    responseFactory = new HttpResponseFactory.Builder(objectMapper).build();
  }

  @AfterEach
  void tearDown() {
    server.stop(0);
  }

  @Test
  void getCardShouldNormalizeExactEnglishPrintingAndFinishOrder() {
    // arrange
    nextBody = card(CARD_ID, "Lightning Bolt", "en", List.of("etched", "foil", "nonfoil"), true);

    // act
    var result = catalog.getCard(CARD_ID);

    // assert
    assertThat(result.externalId()).isEqualTo(CARD_ID);
    assertThat(result.externalSource()).isEqualTo("scryfall");
    assertThat(result.name()).isEqualTo("Lightning Bolt");
    assertThat(result.availableFinishes()).containsExactly("normal", "foil", "etched");
    assertThat(result.imageUrls().small())
        .isEqualTo("https://cards.scryfall.io/small/" + CARD_ID + ".jpg");
    assertThat(result.imageUrls().normal())
        .isEqualTo("https://cards.scryfall.io/normal/" + CARD_ID + ".jpg");
    assertThat(requests).extracting(URI::getPath).containsExactly("/cards/" + CARD_ID);
  }

  @Test
  void getImageUrlsShouldBuildRedirectUrlsWithoutRequestingScryfall() {
    // act
    var imageUrls = catalog.getImageUrls(CARD_ID);

    // assert
    assertThat(imageUrls.small())
        .isEqualTo("https://api.scryfall.com/cards/" + CARD_ID + "?format=image&version=small");
    assertThat(imageUrls.normal())
        .isEqualTo("https://api.scryfall.com/cards/" + CARD_ID + "?format=image&version=normal");
    assertThat(requests).isEmpty();
  }

  @Test
  void findCardsShouldReturnExactEnglishCardsAndLeaveMissingOrNonEnglishUnresolved()
      throws Exception {
    // arrange
    var response = objectMapper.createObjectNode();
    response
        .putArray("data")
        .add(cardNode(CARD_ID, "Lightning Bolt", "en", List.of("nonfoil", "foil"), true))
        .add(cardNode(ALT_ID, "Lightning Bolt", "ja", List.of("nonfoil", "foil"), true));
    response.putArray("not_found").addObject().put("id", TOKEN_ID);
    collectionBody = response.toString();

    // act
    var cards = catalog.findCards(List.of(CARD_ID, ALT_ID, TOKEN_ID));

    // assert
    assertThat(cards.keySet()).containsExactly(CARD_ID);
    assertThat(cards.get(CARD_ID).externalId()).isEqualTo(CARD_ID);
    assertThat(cards.get(CARD_ID).availableFinishes()).containsExactly("normal", "foil");
    assertThat(requestMethods).containsExactly("POST");
    assertThat(requests).extracting(URI::getPath).containsExactly("/cards/collection");
    assertThat(lastContentType).isEqualTo("application/json");
    assertThat(collectionRequests.getFirst().get("identifiers").size()).isEqualTo(3);
  }

  @Test
  void findCardsShouldRejectIncompleteOrMismatchedProviderResults() {
    // arrange
    collectionBody = "{\"data\":[],\"not_found\":[]}";

    // act / assert
    assertThatThrownBy(() -> catalog.findCards(List.of(CARD_ID)))
        .isInstanceOf(CatalogException.Unavailable.class);

    collectionBody = "not json";
    assertThatThrownBy(() -> catalog.findCards(List.of(CARD_ID)))
        .isInstanceOf(CatalogException.Unavailable.class);

    var response = objectMapper.createObjectNode();
    response
        .putArray("data")
        .add(cardNode(ALT_ID, "Lightning Bolt", "en", List.of("nonfoil"), true));
    response.putArray("not_found");
    collectionBody = response.toString();
    assertThatThrownBy(() -> catalog.findCards(List.of(CARD_ID)))
        .isInstanceOf(CatalogException.Unavailable.class);
  }

  @Test
  void findCardsShouldSplitTwoHundredIdsIntoScryfallCollectionChunks() {
    // arrange
    var ids = IntStream.range(0, 200).mapToObj(index -> UUID.randomUUID().toString()).toList();

    // act
    var cards = catalog.findCards(ids);

    // assert
    assertThat(cards).hasSize(200);
    assertThat(collectionRequests)
        .extracting(request -> request.get("identifiers").size())
        .containsExactly(75, 75, 50);
    assertThat(requestMethods).containsExactly("POST", "POST", "POST");
  }

  @Test
  void findCardsShouldDeduplicateOnlyIdenticalIds() {
    // arrange
    var uppercaseId = CARD_ID.toUpperCase(Locale.ROOT);

    // act
    var cards = catalog.findCards(List.of(CARD_ID, uppercaseId, CARD_ID));

    // assert
    assertThat(cards.keySet()).containsExactlyInAnyOrder(CARD_ID, uppercaseId);
    assertThat(collectionRequests.getFirst().get("identifiers"))
        .extracting(identifier -> identifier.get("id").asText())
        .containsExactly(CARD_ID, uppercaseId);
  }

  @Test
  void findCardsShouldSurfaceProviderFailures() {
    // arrange
    nextStatus = 429;

    // act / assert
    assertThatThrownBy(() -> catalog.findCards(List.of(CARD_ID)))
        .isInstanceOf(CatalogException.Unavailable.class);
  }

  @Test
  void getCardShouldRejectAProviderResponseForADifferentPrinting() {
    // arrange
    nextBody = card(ALT_ID, "Lightning Bolt", "en", List.of("nonfoil"), true);
    var handler =
        new GetCatalogCardHandler(
            new RequestContextFactory(),
            responseFactory,
            new Catalogs(Map.of("scryfall", catalog)));

    // act
    var response =
        handler.handleRequest(event(Map.of("game", "mtg"), Map.of("external_id", CARD_ID)), null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(503);
    assertThat(requests).extracting(URI::getPath).containsExactly("/cards/" + CARD_ID);
  }

  @Test
  void getCardShouldUseFirstFaceImagesWhenTopLevelImagesAreMissing() throws Exception {
    // arrange
    var card =
        (ObjectNode)
            objectMapper.readTree(
                card(
                    CARD_ID,
                    "Delver of Secrets // Insectile Aberration",
                    "en",
                    List.of("nonfoil"),
                    true));
    card.remove("image_uris");
    var faces = objectMapper.createArrayNode();
    faces
        .addObject()
        .putObject("image_uris")
        .put("small", "https://cards.scryfall.io/small/front.jpg")
        .put("normal", "https://cards.scryfall.io/normal/front.jpg");
    faces
        .addObject()
        .putObject("image_uris")
        .put("normal", "https://cards.scryfall.io/normal/back.jpg");
    card.set("card_faces", faces);
    nextBody = objectMapper.writeValueAsString(card);

    // act
    var result = catalog.getCard(CARD_ID);

    // assert
    assertThat(result.imageUrls().small()).isEqualTo("https://cards.scryfall.io/small/front.jpg");
    assertThat(result.imageUrls().normal()).isEqualTo("https://cards.scryfall.io/normal/front.jpg");
  }

  @Test
  void getCardShouldReturnNullImagesWhenNoFaceHasImages() throws Exception {
    // arrange
    var card =
        (ObjectNode)
            objectMapper.readTree(card(CARD_ID, "No Image", "en", List.of("nonfoil"), true));
    card.remove("image_uris");
    nextBody = objectMapper.writeValueAsString(card);

    // act
    var result = catalog.getCard(CARD_ID);

    // assert
    assertThat(result.imageUrls().small()).isNull();
    assertThat(result.imageUrls().normal()).isNull();
  }

  @Test
  void alternativesShouldPutEligibleAnchorFirstAndFilterIneligibleFinish() {
    // arrange
    nextBody = card(CARD_ID, "Lightning Bolt", "en", List.of("nonfoil", "foil"), true);
    searchBody =
        searchPage(
            List.of(
                cardNode(CARD_ID, "Lightning Bolt", "en", List.of("nonfoil", "foil"), true),
                cardNode(ALT_ID, "Lightning Bolt", "en", List.of("foil"), true),
                cardNode(TOKEN_ID, "Soldier Token", "en", List.of("foil"), true),
                cardNode(
                    "00000000-0000-0000-0000-000000000003",
                    "Digital Lightning Bolt",
                    "en",
                    List.of("foil"),
                    false)),
            false);

    // act
    var result = catalog.findAlternatives(CARD_ID, "foil", null);

    // assert
    assertThat(result.cards())
        .extracting(CatalogCard::externalId)
        .containsExactly(CARD_ID, ALT_ID, TOKEN_ID);
    assertThat(result.nextContinuation()).isNull();
    assertThat(requests).hasSize(2);
    assertThat(decodeQuery(requests.get(1))).contains("lang:en game:paper is:foil");
  }

  @Test
  void alternativesShouldNotIncludeIneligibleAnchor() {
    // arrange
    nextBody = card(CARD_ID, "Lightning Bolt", "en", List.of("nonfoil"), true);
    searchBody =
        searchPage(List.of(cardNode(ALT_ID, "Lightning Bolt", "en", List.of("foil"), true)), false);

    // act
    var result = catalog.findAlternatives(CARD_ID, "etched", null);

    // assert
    assertThat(result.cards()).isEmpty();
    assertThat(result.nextContinuation()).isNull();
  }

  @Test
  void alternativesShouldReturnNotFoundForAnUnknownAnchor() {
    // arrange
    nextStatus = 404;
    var handler =
        new FindCatalogAlternativesHandler(
            new RequestContextFactory(),
            responseFactory,
            new Catalogs(Map.of("scryfall", catalog)));

    // act
    var response =
        handler.handleRequest(
            event(
                Map.of("game", "mtg", "finish", "normal", "continuation", "malformed"),
                Map.of("external_id", CARD_ID)),
            null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(404);
  }

  @Test
  void alternativesShouldNotFollowProviderUrisToAnotherHost() throws Exception {
    // arrange
    var card =
        (ObjectNode)
            objectMapper.readTree(card(CARD_ID, "Lightning Bolt", "en", List.of("nonfoil"), true));
    card.put("prints_search_uri", "https://example.com/cards/search?q=oracleid%3A" + ORACLE_ID);
    nextBody = card.toString();
    var handler =
        new FindCatalogAlternativesHandler(
            new RequestContextFactory(),
            responseFactory,
            new Catalogs(Map.of("scryfall", catalog)));

    // act
    var response =
        handler.handleRequest(
            event(Map.of("game", "mtg", "finish", "normal"), Map.of("external_id", CARD_ID)), null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(503);
    assertThat(requests).hasSize(1);
  }

  @Test
  void searchShouldReturnDistinctTwentyCardPagesAndBindContinuationToQuery() {
    // arrange
    var cards = new ArrayList<JsonNode>();
    for (int i = 1; i <= 25; i++) {
      cards.add(
          cardNode(
              "00000000-0000-0000-0000-%012d".formatted(i),
              "Lightning Bolt " + i,
              "en",
              List.of("nonfoil"),
              true));
    }
    searchBody = searchPage(cards, false);

    // act
    var first = catalog.search("Lightning Bolt", "normal", null);
    var second = catalog.search("Lightning Bolt", "normal", first.nextContinuation());
    var mismatched =
        new FindCatalogCardsHandler(
                new RequestContextFactory(),
                responseFactory,
                new Catalogs(Map.of("scryfall", catalog)))
            .handleRequest(
                event(
                    Map.of(
                        "game",
                        "mtg",
                        "query",
                        "Different Card",
                        "finish",
                        "normal",
                        "continuation",
                        first.nextContinuation()),
                    null),
                null);

    // assert
    assertThat(first.cards()).hasSize(20);
    assertThat(first.nextContinuation()).isNotNull();
    assertThat(second.cards()).hasSize(5);
    assertThat(second.nextContinuation()).isNull();
    assertThat(first.cards())
        .extracting(CatalogCard::externalId)
        .doesNotContainAnyElementsOf(second.cards().stream().map(CatalogCard::externalId).toList());
    assertThat(mismatched.getStatusCode()).isEqualTo(400);
    assertThat(decodeQuery(requests.getFirst()))
        .contains("name:\"Lightning Bolt\" lang:en game:paper is:nonfoil");
  }

  @Test
  void searchShouldResolveAnExactIdAndReturnEmptyForUnknownId() {
    // arrange
    nextBody = card(CARD_ID, "Lightning Bolt", "en", List.of("nonfoil"), true);

    // act
    var found = catalog.search(CARD_ID, "normal", null);
    nextStatus = 404;
    var missing = catalog.search(ALT_ID, "normal", null);

    // assert
    assertThat(found.cards()).extracting(CatalogCard::externalId).containsExactly(CARD_ID);
    assertThat(missing.cards()).isEmpty();
    assertThat(missing.nextContinuation()).isNull();
  }

  @Test
  void handlersShouldReturnBadRequestForInvalidOrMismatchedParameters() throws Exception {
    // arrange
    var catalogs = new Catalogs(Map.of("scryfall", catalog));
    var getCard = new GetCatalogCardHandler(new RequestContextFactory(), responseFactory, catalogs);
    var search =
        new FindCatalogCardsHandler(new RequestContextFactory(), responseFactory, catalogs);
    var alternatives =
        new FindCatalogAlternativesHandler(new RequestContextFactory(), responseFactory, catalogs);

    // act
    var missingGame = getCard.handleRequest(event(Map.of(), Map.of("external_id", CARD_ID)), null);
    var malformedId =
        getCard.handleRequest(event(Map.of("game", "mtg"), Map.of("external_id", "bad-id")), null);
    var unsupportedGame =
        search.handleRequest(
            event(Map.of("game", "pokemon", "query", "Mew", "finish", "normal"), null), null);
    var unsupportedFinish =
        search.handleRequest(
            event(Map.of("game", "mtg", "query", "Mew", "finish", "reverse_holofoil"), null), null);
    var missingQuery =
        search.handleRequest(event(Map.of("game", "mtg", "finish", "normal"), null), null);
    var malformedContinuation =
        search.handleRequest(
            event(
                Map.of(
                    "game",
                    "mtg",
                    "query",
                    "Mew",
                    "finish",
                    "normal",
                    "continuation",
                    "https://api.scryfall.com/cards/search"),
                null),
            null);
    var unsupportedAlternativeFinish =
        alternatives.handleRequest(
            event(Map.of("game", "mtg", "finish", "bad"), Map.of("external_id", CARD_ID)), null);

    // assert
    assertThat(missingGame.getStatusCode()).isEqualTo(400);
    assertThat(malformedId.getStatusCode()).isEqualTo(400);
    assertThat(unsupportedGame.getStatusCode()).isEqualTo(400);
    assertThat(unsupportedFinish.getStatusCode()).isEqualTo(400);
    assertThat(missingQuery.getStatusCode()).isEqualTo(400);
    assertThat(malformedContinuation.getStatusCode()).isEqualTo(400);
    assertThat(unsupportedAlternativeFinish.getStatusCode()).isEqualTo(400);
  }

  @Test
  void handlersShouldReturnNotFoundForUnknownAndNonEnglishExactCards() throws Exception {
    // arrange
    var getCard =
        new GetCatalogCardHandler(
            new RequestContextFactory(),
            responseFactory,
            new Catalogs(Map.of("scryfall", catalog)));

    // act
    nextStatus = 404;
    var missing =
        getCard.handleRequest(event(Map.of("game", "mtg"), Map.of("external_id", CARD_ID)), null);
    nextStatus = 200;
    nextBody = card(CARD_ID, "Lightning Bolt", "ja", List.of("nonfoil"), true);
    var nonEnglish =
        getCard.handleRequest(event(Map.of("game", "mtg"), Map.of("external_id", CARD_ID)), null);

    // assert
    assertThat(missing.getStatusCode()).isEqualTo(404);
    assertThat(nonEnglish.getStatusCode()).isEqualTo(404);
  }

  @Test
  void handlerShouldReturnServiceUnavailableForRateLimitProviderFailureAndMalformedData() {
    // arrange
    var getCard =
        new GetCatalogCardHandler(
            new RequestContextFactory(),
            responseFactory,
            new Catalogs(Map.of("scryfall", catalog)));

    // act
    nextStatus = 429;
    var rateLimited =
        getCard.handleRequest(event(Map.of("game", "mtg"), Map.of("external_id", CARD_ID)), null);
    nextStatus = 500;
    var providerError =
        getCard.handleRequest(event(Map.of("game", "mtg"), Map.of("external_id", CARD_ID)), null);
    nextStatus = 200;
    nextBody = "{not-json";
    var malformed =
        getCard.handleRequest(event(Map.of("game", "mtg"), Map.of("external_id", CARD_ID)), null);
    var search =
        new FindCatalogCardsHandler(
            new RequestContextFactory(),
            responseFactory,
            new Catalogs(Map.of("scryfall", catalog)));
    searchBody = "{\"object\":\"list\",\"data\":[]}";
    var malformedSearch =
        search.handleRequest(
            event(Map.of("game", "mtg", "query", "Lightning Bolt", "finish", "normal"), null),
            null);

    // assert
    assertThat(rateLimited.getStatusCode()).isEqualTo(503);
    assertThat(providerError.getStatusCode()).isEqualTo(503);
    assertThat(malformed.getStatusCode()).isEqualTo(503);
    assertThat(malformedSearch.getStatusCode()).isEqualTo(503);
  }

  @Test
  void handlerShouldReturnServiceUnavailableWhenProviderDoesNotRespond() throws Exception {
    // arrange
    var endpoint = baseUri;
    server.stop(0);
    var unavailableCatalog =
        new ScryfallCatalog(endpoint, HttpClient.newHttpClient(), objectMapper, () -> {});
    var getCard =
        new GetCatalogCardHandler(
            new RequestContextFactory(),
            responseFactory,
            new Catalogs(Map.of("scryfall", unavailableCatalog)));

    // act
    var response =
        getCard.handleRequest(event(Map.of("game", "mtg"), Map.of("external_id", CARD_ID)), null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(503);
  }

  @Test
  void handlerShouldReturnServiceUnavailableWhenProviderTimesOut() {
    // arrange
    nextDelayMillis = 6000;
    var getCard =
        new GetCatalogCardHandler(
            new RequestContextFactory(),
            responseFactory,
            new Catalogs(Map.of("scryfall", catalog)));

    // act
    var response =
        getCard.handleRequest(event(Map.of("game", "mtg"), Map.of("external_id", CARD_ID)), null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(503);
  }

  @Test
  void providerRequestsShouldIncludeRequiredHeaders() {
    // arrange
    nextBody = card(CARD_ID, "Lightning Bolt", "en", List.of("nonfoil"), true);

    // act
    catalog.getCard(CARD_ID);

    // assert
    assertThat(lastUserAgent).startsWith("TcgInventory/");
    assertThat(lastAccept).contains("application/json");
  }

  private String lastUserAgent;
  private String lastAccept;
  private String lastContentType;

  private void handleRequest(HttpExchange exchange) throws IOException {
    requests.add(exchange.getRequestURI());
    requestMethods.add(exchange.getRequestMethod());
    lastUserAgent = exchange.getRequestHeaders().getFirst("User-Agent");
    lastAccept = exchange.getRequestHeaders().getFirst("Accept");
    lastContentType = exchange.getRequestHeaders().getFirst("Content-Type");
    var status = nextStatus;
    nextStatus = 200;
    if (nextDelayMillis > 0) {
      try {
        Thread.sleep(nextDelayMillis);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IOException(e);
      }
      nextDelayMillis = 0;
    }
    var isSearch = "/cards/search".equals(exchange.getRequestURI().getPath());
    var isCollection = "/cards/collection".equals(exchange.getRequestURI().getPath());
    String body;
    if (isCollection) {
      var request = objectMapper.readTree(exchange.getRequestBody());
      collectionRequests.add(request);
      body = collectionBody != null ? collectionBody : collectionResponse(request);
      collectionBody = null;
    } else if (isSearch) {
      body = searchBody;
    } else {
      body = nextBody;
      nextBody = null;
    }
    if (body == null) {
      body =
          isCollection
              ? "{\"data\":[],\"not_found\":[]}"
              : isSearch
                  ? searchPage(
                      List.of(
                          cardNode(
                              CARD_ID,
                              "Lightning Bolt",
                              "en",
                              List.of("nonfoil", "foil", "etched"),
                              true)),
                      false)
                  : card(
                      CARD_ID, "Lightning Bolt", "en", List.of("nonfoil", "foil", "etched"), true);
    }
    respond(exchange, status, body);
  }

  private String collectionResponse(JsonNode request) {
    var response = objectMapper.createObjectNode();
    var data = response.putArray("data");
    response.putArray("not_found");
    for (var identifier : request.get("identifiers")) {
      data.add(
          cardNode(
              identifier.get("id").asText(),
              "Lightning Bolt",
              "en",
              List.of("nonfoil", "foil", "etched"),
              true));
    }
    return response.toString();
  }

  private APIGatewayV2HTTPEvent event(Map<String, String> query, Map<String, String> path) {
    return APIGatewayV2HTTPEvent.builder()
        .withHeaders(Map.of("Authorization", AUTH_HEADER))
        .withQueryStringParameters(query)
        .withPathParameters(path)
        .build();
  }

  private String card(
      String id, String name, String language, List<String> finishes, boolean paper) {
    var card = cardNode(id, name, language, finishes, paper);
    ((ObjectNode) card).put("prints_search_uri", printsSearchUri());
    return card.toString();
  }

  private JsonNode cardNode(
      String id, String name, String language, List<String> finishes, boolean paper) {
    var card = objectMapper.createObjectNode();
    card.put("id", id);
    card.put("name", name);
    card.put("lang", language);
    card.put("set", "sta");
    card.put("set_name", "Strixhaven Mystical Archive");
    card.put("collector_number", "42");
    var finishArray = card.putArray("finishes");
    finishes.forEach(finishArray::add);
    var games = card.putArray("games");
    if (paper) {
      games.add("paper");
    } else {
      games.add("arena");
    }
    var imageUris = card.putObject("image_uris");
    imageUris.put("small", "https://cards.scryfall.io/small/" + id + ".jpg");
    imageUris.put("normal", "https://cards.scryfall.io/normal/" + id + ".jpg");
    card.put("prints_search_uri", printsSearchUri());
    return card;
  }

  private String searchPage(List<JsonNode> cards, boolean hasMore) {
    var page = objectMapper.createObjectNode();
    ArrayNode data = page.putArray("data");
    cards.forEach(data::add);
    page.put("has_more", hasMore);
    return page.toString();
  }

  private String printsSearchUri() {
    return baseUri + "/cards/search?order=released&q=oracleid%3A" + ORACLE_ID + "&unique=prints";
  }

  private String decodeQuery(URI uri) {
    var query = uri.getRawQuery();
    for (var parameter : query.split("&")) {
      var pair = parameter.split("=", 2);
      if ("q".equals(pair[0])) {
        return URLDecoder.decode(pair[1], StandardCharsets.UTF_8);
      }
    }
    return "";
  }

  private void respond(HttpExchange exchange, int status, String response) throws IOException {
    var bytes = response.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", "application/json");
    exchange.sendResponseHeaders(status, bytes.length);
    exchange.getResponseBody().write(bytes);
    exchange.close();
  }
}
