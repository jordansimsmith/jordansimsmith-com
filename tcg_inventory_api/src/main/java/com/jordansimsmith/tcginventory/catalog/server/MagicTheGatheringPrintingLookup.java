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

public class MagicTheGatheringPrintingLookup {
  private static final int COLLECTION_REQUEST_SIZE = 75;
  private static final int PROVIDER_PAGE_SIZE = 175;
  private static final int MAX_PROVIDER_PAGES_PER_REQUEST = 5;
  private static final int MAX_PROVIDER_PAGE_NUMBER = 10000;
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

  public MagicTheGatheringPrintingLookup(
      URI baseUri, HttpClient httpClient, ObjectMapper objectMapper) {
    this.baseUri = baseUri;
    this.httpClient = httpClient;
    this.objectMapper = objectMapper;
  }

  public static ImageUrls getCardImages(String externalId) {
    var encodedExternalId = URLEncoder.encode(externalId, UTF_8).replace("+", "%20");
    var imageUrl = "https://api.scryfall.com/cards/" + encodedExternalId + "?format=image&version=";
    return new ImageUrls(imageUrl + "small", imageUrl + "normal");
  }

  public Card getCard(Game game, String externalId) throws ServiceException {
    if (externalId == null || !EXTERNAL_ID.matcher(externalId).matches()) {
      throw new BadRequestException("external_id must be a valid Scryfall ID");
    }
    try {
      var card = readCard(externalId);
      if (card.language() == null) {
        throw new ServiceUnavailableException("catalog returned incomplete card data");
      }
      if (!game.language().code().equals(card.language())) {
        throw new NotFoundException("card not found");
      }
      if (card.name() == null
          || card.setCode() == null
          || card.setName() == null
          || card.collectorNumber() == null
          || card.finishes() == null
          || card.games() == null) {
        throw new ServiceUnavailableException("catalog returned incomplete card data");
      }
      var finishes =
          card.games().contains("paper")
              ? game.finishes().stream()
                  .map(Finish::id)
                  .filter(
                      finish ->
                          card.finishes().contains("normal".equals(finish) ? "nonfoil" : finish))
                  .toList()
              : List.<String>of();
      return new Card(
          game.id(),
          card.id(),
          card.name(),
          card.setCode(),
          card.setName(),
          card.collectorNumber(),
          imageUrls(card),
          finishes);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new ServiceUnavailableException("catalog is temporarily unavailable", e);
    } catch (IOException | ServiceUnavailableException e) {
      throw new ServiceUnavailableException("catalog is temporarily unavailable", e);
    }
  }

  public List<CatalogPrinting> findCards(Game game, List<String> externalIds)
      throws ServiceUnavailableException {
    try {
      return doFindCards(game, externalIds);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new ServiceUnavailableException("catalog collection lookup was interrupted", e);
    } catch (IOException e) {
      throw new ServiceUnavailableException("catalog collection lookup failed", e);
    }
  }

  public SearchCardsResponse searchCards(
      Game game, String query, String finish, int limit, String continuation)
      throws ServiceException {
    var position = decodePosition(continuation);
    var providerQuery =
        "name:\"%s\" lang:%s game:paper is:%s"
            .formatted(escapeSearchPhrase(query), game.language().code(), scryfallFinish(finish));
    try {
      return readSearchPages(game, providerQuery, position, finish, limit, null, null);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new ServiceUnavailableException("catalog is temporarily unavailable", e);
    } catch (IOException e) {
      throw new ServiceUnavailableException("catalog is temporarily unavailable", e);
    } catch (ServiceUnavailableException e) {
      throw new ServiceUnavailableException("catalog is temporarily unavailable", e);
    }
  }

  public FindCardAlternativesResponse findCardAlternatives(
      Game game, String externalId, String finish, int limit, String continuation)
      throws ServiceException {
    if (externalId == null || !EXTERNAL_ID.matcher(externalId).matches()) {
      throw new BadRequestException("external_id must be a valid Scryfall ID");
    }
    try {
      return doFindCardAlternatives(game, externalId, finish, limit, continuation);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new ServiceUnavailableException("catalog is temporarily unavailable", e);
    } catch (IOException | ServiceUnavailableException e) {
      throw new ServiceUnavailableException("catalog is temporarily unavailable", e);
    }
  }

  private FindCardAlternativesResponse doFindCardAlternatives(
      Game game, String externalId, String finish, int limit, String continuation)
      throws IOException, InterruptedException, ServiceException {
    var anchor = readCard(externalId);
    if (anchor.language() == null) {
      throw new ServiceUnavailableException("catalog returned incomplete card data");
    }
    if (!game.language().code().equals(anchor.language())) {
      throw new NotFoundException("card not found");
    }

    var anchorCard = toSearchCard(game, anchor);
    var query =
        "%s lang:%s game:paper is:%s"
            .formatted(
                queryFromPrintsUri(anchor.printsSearchUri()),
                game.language().code(),
                scryfallFinish(finish));
    var position = decodePosition(continuation);
    var includeAnchor = continuation == null && anchorCard.availableFinishes().contains(finish);
    var page =
        readSearchPages(
            game, query, position, finish, limit, includeAnchor ? anchorCard : null, externalId);
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
      throws IOException, InterruptedException, ServiceException {
    var cards = new ArrayList<Card>();
    var seen = new HashSet<String>();
    if (first != null) {
      cards.add(first);
      seen.add(first.externalId());
      if (cards.size() == limit) {
        return new SearchCardsResponse(List.copyOf(cards), encodePosition(start));
      }
    }
    int pageNumber = start.page();
    int offset = start.offset();
    int providerPagesRead = 0;
    while (cards.size() < limit) {
      if (providerPagesRead == MAX_PROVIDER_PAGES_PER_REQUEST) {
        return new SearchCardsResponse(
            List.copyOf(cards), encodePosition(new Position(pageNumber, offset)));
      }
      providerPagesRead++;
      var page = readSearchPage(query, pageNumber);
      if (page.data() == null
          || page.data().size() > PROVIDER_PAGE_SIZE
          || page.hasMore() == null) {
        throw new ServiceUnavailableException("catalog returned invalid search data");
      }
      if (offset > page.data().size()) {
        throw new ServiceUnavailableException("catalog changed while paging results");
      }
      for (int index = offset; index < page.data().size(); index++) {
        var candidate = page.data().get(index);
        offset = index + 1;
        if (candidate == null || candidate.language() == null) {
          throw new ServiceUnavailableException("catalog returned invalid card data");
        }
        if (!game.language().code().equals(candidate.language())) {
          continue;
        }
        var card = toSearchCard(game, candidate);
        if ((excludedId != null && excludedId.equals(card.externalId()))
            || !card.availableFinishes().contains(finish)
            || !seen.add(card.externalId())) {
          continue;
        }
        cards.add(card);
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
    throw new IllegalStateException("catalog page exceeded its configured size");
  }

  private ScryfallSearchPage readSearchPage(String query, int page)
      throws IOException, InterruptedException, ServiceUnavailableException {
    var url =
        baseUri
            .resolve(
                "/cards/search?q="
                    + encode(query)
                    + "&unique=prints&include_extras=true&include_variations=true&order=released&dir=desc&page="
                    + page)
            .toString();
    var request =
        HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(Duration.ofSeconds(5))
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
            .GET()
            .build();
    paceRequest();
    var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(UTF_8));
    if (response.statusCode() == 404) {
      return new ScryfallSearchPage(List.of(), false);
    }
    if (response.statusCode() != 200) {
      throw new ServiceUnavailableException(
          "catalog provider returned status " + response.statusCode());
    }
    var result = objectMapper.readValue(response.body(), ScryfallSearchPage.class);
    if (result == null || result.data() == null) {
      throw new ServiceUnavailableException("catalog returned invalid search data");
    }
    return result;
  }

  private Card toSearchCard(Game game, ScryfallCard card) throws ServiceUnavailableException {
    if (card.id() == null
        || !EXTERNAL_ID.matcher(card.id()).matches()
        || card.name() == null
        || card.setCode() == null
        || card.setName() == null
        || card.collectorNumber() == null
        || card.finishes() == null
        || card.games() == null) {
      throw new ServiceUnavailableException("catalog returned incomplete card data");
    }
    var finishes =
        card.games().contains("paper")
            ? game.finishes().stream()
                .filter(candidate -> card.finishes().contains(scryfallFinish(candidate.id())))
                .map(Finish::id)
                .toList()
            : List.<String>of();
    return new Card(
        game.id(),
        card.id(),
        card.name(),
        card.setCode(),
        card.setName(),
        card.collectorNumber(),
        imageUrls(card),
        finishes);
  }

  private Position decodePosition(String continuation) throws ServiceException {
    if (continuation == null) {
      return new Position(1, 0);
    }
    try {
      var decoded = new String(Base64.getUrlDecoder().decode(continuation), UTF_8).split(":", -1);
      if (decoded.length != 2) {
        throw new IllegalArgumentException("continuation position is invalid");
      }
      var page = Integer.parseInt(decoded[0]);
      var offset = Integer.parseInt(decoded[1]);
      if (page < 1
          || page > MAX_PROVIDER_PAGE_NUMBER
          || offset < 0
          || offset >= PROVIDER_PAGE_SIZE) {
        throw new IllegalArgumentException("continuation position is invalid");
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
      throw new ServiceUnavailableException("catalog card is missing printing search data");
    }
    try {
      var uri = new URIBuilder(URI.create(printsSearchUri));
      if (!baseUri.getScheme().equalsIgnoreCase(uri.getScheme())
          || !baseUri.getHost().equalsIgnoreCase(uri.getHost())
          || !"/cards/search".equals(uri.getPath())) {
        throw new ServiceUnavailableException("catalog returned an invalid printing search URI");
      }
      var query = uri.getFirstQueryParam("q");
      if (query == null) {
        throw new ServiceUnavailableException("catalog returned an invalid printing search URI");
      }
      return query.getValue();
    } catch (IllegalArgumentException e) {
      throw new ServiceUnavailableException("catalog returned an invalid printing search URI", e);
    }
  }

  private String encode(String value) {
    return URLEncoder.encode(value, UTF_8).replace("+", "%20");
  }

  private String scryfallFinish(String finish) {
    return "normal".equals(finish) ? "nonfoil" : finish;
  }

  private List<CatalogPrinting> doFindCards(Game game, List<String> externalIds)
      throws IOException, InterruptedException, ServiceUnavailableException {
    var ids = externalIds.stream().filter(id -> EXTERNAL_ID.matcher(id).matches()).toList();
    var cards = new ArrayList<CatalogPrinting>();
    for (var chunk : Lists.partition(ids, COLLECTION_REQUEST_SIZE)) {
      var response = readCollection(chunk);
      var unresolved = new HashSet<>(chunk);
      for (var card : response.data()) {
        if (card == null
            || card.name() == null
            || card.setCode() == null
            || card.setName() == null
            || card.collectorNumber() == null
            || card.language() == null
            || card.games() == null
            || card.finishes() == null) {
          throw new ServiceUnavailableException("catalog returned incomplete card data");
        }
        resolveId(unresolved, card.id());
        if (game.language().code().equals(card.language()) && card.games().contains("paper")) {
          var finishes =
              game.finishes().stream()
                  .map(Finish::id)
                  .filter(
                      finish ->
                          card.finishes().contains("normal".equals(finish) ? "nonfoil" : finish))
                  .toList();
          if (!finishes.isEmpty()) {
            cards.add(
                new CatalogPrinting(
                    game.id(),
                    card.id(),
                    card.name(),
                    card.setCode(),
                    card.setName(),
                    card.collectorNumber(),
                    finishes));
          }
        }
      }
      if (response.notFound() != null) {
        for (var missing : response.notFound()) {
          resolveId(unresolved, missing == null ? null : missing.id());
        }
      }
      if (!unresolved.isEmpty()) {
        throw new ServiceUnavailableException("catalog returned incomplete collection data");
      }
    }
    return List.copyOf(cards);
  }

  private CardCollection readCollection(List<String> ids)
      throws IOException, InterruptedException, ServiceUnavailableException {
    var identifiers = ids.stream().map(Identifier::new).toList();
    var body = objectMapper.writeValueAsString(new CollectionRequest(identifiers));
    var request =
        HttpRequest.newBuilder()
            .uri(baseUri.resolve("/cards/collection"))
            .timeout(Duration.ofSeconds(5))
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body, UTF_8))
            .build();
    paceRequest();
    var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(UTF_8));
    if (response.statusCode() != 200) {
      throw new ServiceUnavailableException(
          "catalog provider returned status " + response.statusCode());
    }
    var collection = objectMapper.readValue(response.body(), CardCollection.class);
    if (collection == null || collection.data() == null) {
      throw new ServiceUnavailableException("catalog returned invalid collection data");
    }
    return collection;
  }

  private ScryfallCard readCard(String externalId)
      throws IOException, InterruptedException, ServiceException {
    var request =
        HttpRequest.newBuilder()
            .uri(baseUri.resolve("/cards/" + externalId))
            .timeout(Duration.ofSeconds(5))
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
            .GET()
            .build();
    paceRequest();
    var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(UTF_8));
    if (response.statusCode() == 404) {
      throw new NotFoundException("card not found");
    }
    if (response.statusCode() != 200) {
      throw new ServiceUnavailableException(
          "catalog provider returned status " + response.statusCode());
    }
    var card = objectMapper.readValue(response.body(), ScryfallCard.class);
    if (card == null || !externalId.equals(card.id())) {
      throw new ServiceUnavailableException("catalog returned a different card ID");
    }
    return card;
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
      throw new ServiceUnavailableException("catalog returned inconsistent collection data");
    }
  }
}
