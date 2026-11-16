package com.jordansimsmith.tcginventory.catalog.server;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.Lists;
import com.jordansimsmith.service.BadRequestException;
import com.jordansimsmith.service.NotFoundException;
import com.jordansimsmith.service.ServiceException;
import com.jordansimsmith.service.ServiceUnavailableException;
import com.jordansimsmith.tcginventory.catalog.client.Card;
import com.jordansimsmith.tcginventory.catalog.client.CatalogPrinting;
import com.jordansimsmith.tcginventory.catalog.client.FindCardAlternativesResponse;
import com.jordansimsmith.tcginventory.catalog.client.ImageUrls;
import com.jordansimsmith.tcginventory.catalog.client.SearchCardsResponse;
import com.jordansimsmith.tcginventory.games.client.Finish;
import com.jordansimsmith.tcginventory.games.client.Game;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import javax.annotation.Nullable;
import org.apache.hc.core5.net.URIBuilder;

public class ScryfallPrintingLookup {
  private static final int COLLECTION_REQUEST_SIZE = 75;
  private static final int PROVIDER_PAGE_SIZE = 175;
  private static final int MAX_PROVIDER_PAGES_PER_REQUEST = 5;
  private static final int MAX_PROVIDER_PAGE_NUMBER = 10000;
  private static final String UNAVAILABLE = "catalog is temporarily unavailable";
  private static final String USER_AGENT =
      "TcgInventory/1.0 (https://tcg-inventory.jordansimsmith.com)";
  private static final Pattern EXTERNAL_ID =
      Pattern.compile(
          "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

  @JsonIgnoreProperties(ignoreUnknown = true)
  private record ScryfallCard(
      @JsonProperty("id") String id,
      @JsonProperty("name") String name,
      @JsonProperty("lang") String language,
      @JsonProperty("set") String setCode,
      @JsonProperty("set_name") String setName,
      @JsonProperty("collector_number") String collectorNumber,
      @JsonProperty("finishes") List<String> finishes,
      @JsonProperty("games") List<String> games,
      @JsonProperty("image_uris") ScryfallImageUris imageUris,
      @JsonProperty("card_faces") List<ScryfallCardFace> cardFaces,
      @JsonProperty("prints_search_uri") String printsSearchUri) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  private record ScryfallImageUris(
      @JsonProperty("small") String small, @JsonProperty("normal") String normal) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  private record ScryfallCardFace(@JsonProperty("image_uris") ScryfallImageUris imageUris) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  private record Identifier(@JsonProperty("id") String id) {}

  private record CollectionRequest(@JsonProperty("identifiers") List<Identifier> identifiers) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  private record ScryfallSearchPage(
      @JsonProperty("data") List<ScryfallCard> data, @JsonProperty("has_more") Boolean hasMore) {}

  private record Position(int page, int offset) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  private record CardCollection(
      @JsonProperty("data") List<ScryfallCard> data,
      @JsonProperty("not_found") List<Identifier> notFound) {}

  private final URI baseUri;
  private final HttpClient httpClient;
  private final ObjectMapper objectMapper;
  private long lastRequestAt;

  public ScryfallPrintingLookup(URI baseUri, HttpClient httpClient, ObjectMapper objectMapper) {
    this.baseUri = baseUri;
    this.httpClient = httpClient;
    this.objectMapper = objectMapper;
  }

  public static ImageUrls getCardImages(String externalId) {
    var imageUrl =
        "https://api.scryfall.com/cards/" + encode(externalId) + "?format=image&version=";
    return new ImageUrls(imageUrl + "small", imageUrl + "normal");
  }

  public Card getCard(Game game, String externalId) throws ServiceException {
    var card = readCard(game, externalId);
    return toCard(toPrinting(game, card), card);
  }

  public List<CatalogPrinting> findCards(Game game, List<String> externalIds)
      throws ServiceUnavailableException {
    var ids = externalIds.stream().filter(id -> EXTERNAL_ID.matcher(id).matches()).toList();
    var cards = new ArrayList<CatalogPrinting>();
    for (var chunk : Lists.partition(ids, COLLECTION_REQUEST_SIZE)) {
      var identifiers = chunk.stream().map(Identifier::new).toList();
      var response =
          read("/cards/collection", new CollectionRequest(identifiers), CardCollection.class);
      if (response == null || response.data() == null) {
        throw new ServiceUnavailableException(UNAVAILABLE);
      }
      var unresolved = new HashSet<>(chunk);
      for (var card : response.data()) {
        var eligibleLanguage = matchesLanguage(game, card);
        resolveId(unresolved, card.id());
        if (!eligibleLanguage) {
          continue;
        }
        var printing = toPrinting(game, card);
        if (!printing.availableFinishes().isEmpty()) {
          cards.add(printing);
        }
      }
      if (response.notFound() != null) {
        for (var missing : response.notFound()) {
          resolveId(unresolved, missing == null ? null : missing.id());
        }
      }
      if (!unresolved.isEmpty()) {
        throw new ServiceUnavailableException(UNAVAILABLE);
      }
    }
    return List.copyOf(cards);
  }

  public SearchCardsResponse searchCards(
      Game game, String query, String finish, int limit, @Nullable String continuation)
      throws ServiceException {
    var position = decodePosition(continuation);
    var providerQuery = filteredQuery(game, "name:\"" + escapeSearchPhrase(query) + "\"", finish);
    return readSearchPages(game, providerQuery, position, finish, limit, null, null);
  }

  public FindCardAlternativesResponse findCardAlternatives(
      Game game, String externalId, String finish, int limit, @Nullable String continuation)
      throws ServiceException {
    var anchor = readCard(game, externalId);
    var printing = toPrinting(game, anchor);
    var query = filteredQuery(game, queryFromPrintsUri(anchor.printsSearchUri()), finish);
    var position = decodePosition(continuation);
    var first =
        continuation == null && printing.availableFinishes().contains(finish)
            ? toCard(printing, anchor)
            : null;
    var page = readSearchPages(game, query, position, finish, limit, first, externalId);
    return new FindCardAlternativesResponse(page.cards(), page.nextContinuation());
  }

  private SearchCardsResponse readSearchPages(
      Game game,
      String query,
      Position start,
      String finish,
      int limit,
      @Nullable Card first,
      @Nullable String excludedId)
      throws ServiceUnavailableException {
    var cards = new ArrayList<Card>();
    var seen = new HashSet<String>();
    if (first != null) {
      cards.add(first);
      if (cards.size() == limit) {
        return new SearchCardsResponse(List.copyOf(cards), encodePosition(start));
      }
    }
    var pageNumber = start.page();
    var offset = start.offset();
    for (var pagesRead = 0; pagesRead < MAX_PROVIDER_PAGES_PER_REQUEST; pagesRead++) {
      var page = readSearchPage(query, pageNumber);
      if (page.data() == null
          || page.data().size() > PROVIDER_PAGE_SIZE
          || page.hasMore() == null
          || offset > page.data().size()) {
        throw new ServiceUnavailableException(UNAVAILABLE);
      }
      for (var index = offset; index < page.data().size(); index++) {
        var candidate = page.data().get(index);
        offset = index + 1;
        if (!matchesLanguage(game, candidate)) {
          continue;
        }
        var printing = toPrinting(game, candidate);
        if (printing.externalId().equals(excludedId)
            || !printing.availableFinishes().contains(finish)
            || !seen.add(printing.externalId())) {
          continue;
        }
        cards.add(toCard(printing, candidate));
        if (cards.size() == limit) {
          var next = nextPosition(pageNumber, offset, page);
          return new SearchCardsResponse(
              List.copyOf(cards), next == null ? null : encodePosition(next));
        }
      }
      if (!page.hasMore()) {
        return new SearchCardsResponse(List.copyOf(cards), null);
      }
      pageNumber++;
      offset = 0;
    }
    return new SearchCardsResponse(
        List.copyOf(cards), encodePosition(new Position(pageNumber, offset)));
  }

  private ScryfallSearchPage readSearchPage(String query, int page)
      throws ServiceUnavailableException {
    var path =
        "/cards/search?q="
            + encode(query)
            + "&unique=prints&include_extras=true&include_variations=true&order=released&dir=desc&page="
            + page;
    var result = read(path, null, ScryfallSearchPage.class);
    return result == null ? new ScryfallSearchPage(List.of(), false) : result;
  }

  private boolean matchesLanguage(Game game, ScryfallCard card) throws ServiceUnavailableException {
    if (card == null || card.language() == null) {
      throw new ServiceUnavailableException(UNAVAILABLE);
    }
    return game.language().code().equals(card.language());
  }

  private CatalogPrinting toPrinting(Game game, ScryfallCard card)
      throws ServiceUnavailableException {
    if (card.id() == null
        || card.name() == null
        || card.setCode() == null
        || card.setName() == null
        || card.collectorNumber() == null
        || card.finishes() == null
        || card.games() == null) {
      throw new ServiceUnavailableException(UNAVAILABLE);
    }
    var finishes =
        card.games().contains("paper")
            ? game.finishes().stream()
                .map(Finish::id)
                .filter(finish -> card.finishes().contains(scryfallFinish(finish)))
                .toList()
            : List.<String>of();
    return new CatalogPrinting(
        game.id(),
        card.id(),
        card.name(),
        card.setCode(),
        card.setName(),
        card.collectorNumber(),
        finishes);
  }

  private Card toCard(CatalogPrinting printing, ScryfallCard card) {
    return new Card(
        printing.game(),
        printing.externalId(),
        printing.name(),
        printing.setCode(),
        printing.setName(),
        printing.collectorNumber(),
        imageUrls(card),
        printing.availableFinishes());
  }

  private Position decodePosition(@Nullable String continuation) throws BadRequestException {
    if (continuation == null) {
      return new Position(1, 0);
    }
    try {
      var decoded = new String(Base64.getUrlDecoder().decode(continuation), UTF_8).split(":", -1);
      if (decoded.length != 2) {
        throw new BadRequestException("continuation is invalid");
      }
      var page = Integer.parseInt(decoded[0]);
      var offset = Integer.parseInt(decoded[1]);
      if (page < 1
          || page > MAX_PROVIDER_PAGE_NUMBER
          || offset < 0
          || offset >= PROVIDER_PAGE_SIZE) {
        throw new BadRequestException("continuation is invalid");
      }
      return new Position(page, offset);
    } catch (IllegalArgumentException e) {
      throw new BadRequestException("continuation is invalid");
    }
  }

  private Position nextPosition(int pageNumber, int offset, ScryfallSearchPage page) {
    if (offset < page.data().size()) {
      return new Position(pageNumber, offset);
    }
    return page.hasMore() ? new Position(pageNumber + 1, 0) : null;
  }

  private String encodePosition(Position position) {
    var token = "%d:%d".formatted(position.page(), position.offset());
    return Base64.getUrlEncoder().withoutPadding().encodeToString(token.getBytes(UTF_8));
  }

  private String escapeSearchPhrase(String query) {
    return query.replace("\\", "\\\\").replace("\"", "\\\"");
  }

  private String queryFromPrintsUri(String printsSearchUri) throws ServiceUnavailableException {
    if (printsSearchUri == null) {
      throw new ServiceUnavailableException(UNAVAILABLE);
    }
    try {
      var query = new URIBuilder(URI.create(printsSearchUri)).getFirstQueryParam("q");
      if (query == null || query.getValue() == null || query.getValue().isBlank()) {
        throw new ServiceUnavailableException(UNAVAILABLE);
      }
      return query.getValue();
    } catch (IllegalArgumentException e) {
      throw new ServiceUnavailableException(UNAVAILABLE, e);
    }
  }

  private String filteredQuery(Game game, String phrase, String finish) {
    return "%s lang:%s game:paper is:%s"
        .formatted(phrase, game.language().code(), scryfallFinish(finish));
  }

  private static String encode(String value) {
    return URLEncoder.encode(value, UTF_8).replace("+", "%20");
  }

  private String scryfallFinish(String finish) {
    return "normal".equals(finish) ? "nonfoil" : finish;
  }

  private ScryfallCard readCard(Game game, String externalId) throws ServiceException {
    if (!EXTERNAL_ID.matcher(externalId).matches()) {
      throw new BadRequestException("external_id must be a valid Scryfall ID");
    }
    var card = read("/cards/" + externalId, null, ScryfallCard.class);
    if (card == null) {
      throw new NotFoundException("card not found");
    }
    if (!externalId.equals(card.id())) {
      throw new ServiceUnavailableException(UNAVAILABLE);
    }
    if (!matchesLanguage(game, card)) {
      throw new NotFoundException("card not found");
    }
    return card;
  }

  @Nullable
  private <T> T read(String path, @Nullable CollectionRequest body, Class<T> type)
      throws ServiceUnavailableException {
    var request =
        HttpRequest.newBuilder(baseUri.resolve(path))
            .timeout(Duration.ofSeconds(5))
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json");
    try {
      if (body != null) {
        request
            .header("Content-Type", "application/json")
            .POST(
                HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body), UTF_8));
      }
      paceRequest();
      var response = httpClient.send(request.build(), HttpResponse.BodyHandlers.ofString(UTF_8));
      if (response.statusCode() == 404 && body == null) {
        return null;
      }
      if (response.statusCode() != 200) {
        throw new IOException("Scryfall returned HTTP " + response.statusCode());
      }
      var result = objectMapper.readValue(response.body(), type);
      if (result == null) {
        throw new ServiceUnavailableException(UNAVAILABLE);
      }
      return result;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new ServiceUnavailableException(UNAVAILABLE, e);
    } catch (IOException e) {
      throw new ServiceUnavailableException(UNAVAILABLE, e);
    }
  }

  private ImageUrls imageUrls(ScryfallCard card) {
    var small = card.imageUris() == null ? null : card.imageUris().small();
    var normal = card.imageUris() == null ? null : card.imageUris().normal();
    if (card.cardFaces() != null && !card.cardFaces().isEmpty()) {
      var faceImageUris = card.cardFaces().getFirst().imageUris();
      if (faceImageUris != null) {
        if (small == null) {
          small = faceImageUris.small();
        }
        if (normal == null) {
          normal = faceImageUris.normal();
        }
      }
    }
    return new ImageUrls(small, normal);
  }

  private synchronized void paceRequest() throws InterruptedException {
    var waitMillis = 100 - (System.currentTimeMillis() - lastRequestAt);
    if (waitMillis > 0) {
      Thread.sleep(waitMillis);
    }
    lastRequestAt = System.currentTimeMillis();
  }

  private void resolveId(Set<String> unresolved, String id) throws ServiceUnavailableException {
    if (!unresolved.remove(id)) {
      throw new ServiceUnavailableException(UNAVAILABLE);
    }
  }
}
