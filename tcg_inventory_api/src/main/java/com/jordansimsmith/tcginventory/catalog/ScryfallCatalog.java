package com.jordansimsmith.tcginventory.catalog;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jordansimsmith.tcginventory.games.Games;
import com.jordansimsmith.tcginventory.games.Games.Finish;
import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Pattern;
import javax.annotation.Nullable;

public class ScryfallCatalog implements CardCatalog {
  private static final String GAME = "mtg";
  private static final String SOURCE = "scryfall";
  private static final String USER_AGENT =
      "TcgInventory/1.0 (https://tcg-inventory.jordansimsmith.com)";
  private static final int PAGE_SIZE = 20;
  private static final int PROVIDER_PAGE_SIZE = 175;
  private static final int MAX_PROVIDER_PAGES_PER_REQUEST = 5;
  private static final int MAX_PROVIDER_PAGE_NUMBER = 10000;
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
      @JsonProperty("image_uris") JsonNode imageUris,
      @JsonProperty("card_faces") List<ScryfallCardFace> cardFaces,
      @JsonProperty("prints_search_uri") String printsSearchUri) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  private record ScryfallCardFace(@JsonProperty("image_uris") JsonNode imageUris) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  private record ScryfallPage(
      @JsonProperty("data") List<ScryfallCard> data, @JsonProperty("has_more") Boolean hasMore) {}

  private record Position(int page, int offset) {}

  private final URI baseUri;
  private final HttpClient httpClient;
  private final ObjectMapper objectMapper;
  private final Runnable pacer;

  public ScryfallCatalog(
      URI baseUri, HttpClient httpClient, ObjectMapper objectMapper, Runnable pacer) {
    this.baseUri = baseUri;
    this.httpClient = httpClient;
    this.objectMapper = objectMapper;
    this.pacer = pacer;
  }

  @Override
  public CatalogCard getCard(String externalId) {
    validateExternalId(externalId);
    return normalize(readCard(externalId));
  }

  @Override
  public CatalogPage findAlternatives(
      String externalId, String finish, @Nullable String continuation) {
    validateExternalId(externalId);
    validateFinish(finish);
    var context = "alternatives:" + GAME + ":" + externalId + ":" + finish;
    var anchor = readCard(externalId);
    var anchorCard = normalize(anchor);
    var position = decodePosition(continuation, context);
    var providerQuery = queryFromPrintsUri(anchor.printsSearchUri());
    var query = "%s lang:en game:paper is:%s".formatted(providerQuery, scryfallFinish(finish));
    return readPage(
        query,
        context,
        position,
        eligible(anchorCard, finish) ? anchorCard : null,
        externalId,
        finish);
  }

  @Override
  public CatalogPage search(String rawQuery, String finish, @Nullable String continuation) {
    var query = rawQuery != null ? rawQuery.trim() : "";
    validateFinish(finish);
    if (query.length() < 2 || query.length() > 200) {
      throw new CatalogException.BadRequest("query must contain between 2 and 200 characters");
    }
    var context = "search:" + GAME + ":" + query + ":" + finish;
    var position = decodePosition(continuation, context);
    if (EXTERNAL_ID.matcher(query).matches()) {
      if (continuation != null) {
        throw new CatalogException.BadRequest("continuation is invalid for an exact ID search");
      }
      try {
        var card = normalize(readCard(query));
        if (!eligible(card, finish)) {
          return emptyPage();
        }
        return new CatalogPage(List.of(card), null);
      } catch (CatalogException.NotFound e) {
        return emptyPage();
      }
    }
    var queryString =
        "name:\"%s\" lang:en game:paper is:%s"
            .formatted(escapeSearchPhrase(query), scryfallFinish(finish));
    return readPage(queryString, context, position, null, null, finish);
  }

  private CatalogPage readPage(
      String query,
      String context,
      Position start,
      @Nullable CatalogCard first,
      @Nullable String excludedId,
      String finish) {
    var cards = new ArrayList<CatalogCard>();
    var seen = new HashSet<String>();
    if (first != null) {
      cards.add(first);
      seen.add(first.externalId());
    }

    int pageNumber = start.page();
    int offset = start.offset();
    int providerPagesRead = 0;
    while (cards.size() < PAGE_SIZE) {
      if (providerPagesRead == MAX_PROVIDER_PAGES_PER_REQUEST) {
        return new CatalogPage(cards, encodePosition(context, new Position(pageNumber, offset)));
      }
      providerPagesRead++;
      var page = readSearchPage(query, pageNumber);
      if (page == null
          || page.data() == null
          || page.data().size() > PROVIDER_PAGE_SIZE
          || page.hasMore() == null) {
        throw new CatalogException.Unavailable("catalog returned invalid search data");
      }
      if (offset > page.data().size()) {
        throw new CatalogException.Unavailable("catalog changed while paging results");
      }
      for (int index = offset; index < page.data().size(); index++) {
        var candidate = page.data().get(index);
        offset = index + 1;
        if (candidate == null || candidate.language() == null) {
          throw new CatalogException.Unavailable("catalog returned invalid card data");
        }
        var card = "en".equals(candidate.language()) ? normalize(candidate) : null;
        if (card == null
            || (excludedId != null && excludedId.equals(card.externalId()))
            || !eligible(card, finish)
            || !seen.add(card.externalId())) {
          continue;
        }
        cards.add(card);
        if (cards.size() == PAGE_SIZE) {
          var next = nextPosition(pageNumber, offset, page);
          return new CatalogPage(cards, next == null ? null : encodePosition(context, next));
        }
      }
      if (!page.hasMore()) {
        return new CatalogPage(cards, null);
      }
      pageNumber++;
      offset = 0;
    }
    throw new IllegalStateException("catalog page exceeded its configured size");
  }

  private @Nullable Position nextPosition(int pageNumber, int offset, ScryfallPage page) {
    if (offset < page.data().size()) {
      return new Position(pageNumber, offset);
    }
    return page.hasMore() ? new Position(pageNumber + 1, 0) : null;
  }

  private ScryfallCard readCard(String externalId) {
    var url = baseUri.resolve("/cards/" + externalId).toString();
    try {
      var card = objectMapper.readValue(readResponse(url), ScryfallCard.class);
      if (card == null || card.id() == null || !externalId.equalsIgnoreCase(card.id())) {
        throw new CatalogException.Unavailable("catalog returned a different card ID");
      }
      return card;
    } catch (ScryfallNotFoundException e) {
      throw new CatalogException.NotFound("card not found");
    } catch (CatalogException e) {
      throw e;
    } catch (Exception e) {
      throw new CatalogException.Unavailable("catalog returned invalid card data", e);
    }
  }

  private ScryfallPage readSearchPage(String query, int page) {
    var url =
        baseUri
            .resolve(
                "/cards/search?q="
                    + encode(query)
                    + "&unique=prints&include_extras=true&include_variations=true&order=released&dir=desc&page="
                    + page)
            .toString();
    try {
      return objectMapper.readValue(readResponse(url), ScryfallPage.class);
    } catch (ScryfallNotFoundException e) {
      return new ScryfallPage(List.of(), false);
    } catch (CatalogException e) {
      throw e;
    } catch (Exception e) {
      throw new CatalogException.Unavailable("catalog returned invalid search data", e);
    }
  }

  private String readResponse(String url) {
    try {
      pacer.run();
      var request =
          HttpRequest.newBuilder()
              .uri(URI.create(url))
              .timeout(Duration.ofSeconds(5))
              .header("User-Agent", USER_AGENT)
              .header("Accept", "application/json")
              .GET()
              .build();
      var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(UTF_8));
      if (response.statusCode() == 404) {
        throw new ScryfallNotFoundException();
      }
      if (response.statusCode() != 200) {
        throw new CatalogException.Unavailable(
            "catalog provider returned status " + response.statusCode());
      }
      return response.body();
    } catch (ScryfallNotFoundException | CatalogException e) {
      throw e;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new CatalogException.Unavailable("catalog request was interrupted", e);
    } catch (IOException | IllegalArgumentException e) {
      throw new CatalogException.Unavailable("catalog provider is unavailable", e);
    }
  }

  private CatalogCard normalize(ScryfallCard card) {
    if (card == null
        || card.id() == null
        || card.name() == null
        || card.setCode() == null
        || card.setName() == null
        || card.collectorNumber() == null
        || !EXTERNAL_ID.matcher(card.id()).matches()
        || card.language() == null
        || card.finishes() == null
        || card.games() == null) {
      throw new CatalogException.Unavailable("catalog returned incomplete card data");
    }
    if (!"en".equals(card.language())) {
      throw new CatalogException.NotFound("card not found");
    }
    var game = Games.get(GAME);
    var finishes =
        card.games().contains("paper")
            ? game.finishes().stream()
                .filter(finish -> card.finishes().contains(scryfallFinish(finish.id())))
                .map(Finish::id)
                .toList()
            : List.<String>of();
    return new CatalogCard(
        GAME,
        SOURCE,
        card.id(),
        card.name(),
        card.setCode(),
        card.setName(),
        card.collectorNumber(),
        new CatalogCard.ImageUrls(imageUri(card, "small"), imageUri(card, "normal")),
        finishes);
  }

  private boolean eligible(@Nullable CatalogCard card, String finish) {
    return card != null && card.availableFinishes().contains(finish);
  }

  private String queryFromPrintsUri(String printsSearchUri) {
    if (printsSearchUri == null) {
      throw new CatalogException.Unavailable("catalog card is missing printing search data");
    }
    try {
      var uri = URI.create(printsSearchUri);
      if (!sameProvider(uri) || !"/cards/search".equals(uri.getPath())) {
        throw new CatalogException.Unavailable("catalog returned an invalid printing search URI");
      }
      var rawQuery = uri.getRawQuery();
      if (rawQuery == null) {
        throw new CatalogException.Unavailable("catalog returned an invalid printing search URI");
      }
      for (var parameter : rawQuery.split("&")) {
        var pair = parameter.split("=", 2);
        if (pair.length == 2 && "q".equals(pair[0])) {
          return URLDecoder.decode(pair[1], UTF_8);
        }
      }
      throw new CatalogException.Unavailable("catalog returned an invalid printing search URI");
    } catch (IllegalArgumentException e) {
      throw new CatalogException.Unavailable("catalog returned an invalid printing search URI", e);
    }
  }

  private boolean sameProvider(URI uri) {
    return baseUri.getScheme().equals(uri.getScheme())
        && baseUri.getHost().equals(uri.getHost())
        && effectivePort(baseUri) == effectivePort(uri);
  }

  private int effectivePort(URI uri) {
    return uri.getPort() >= 0 ? uri.getPort() : ("https".equals(uri.getScheme()) ? 443 : 80);
  }

  private Position decodePosition(@Nullable String continuation, String context) {
    if (continuation == null) {
      return new Position(1, 0);
    }
    try {
      var decoded = new String(Base64.getUrlDecoder().decode(continuation), UTF_8).split(":", -1);
      if (decoded.length != 4
          || !"v1".equals(decoded[0])
          || !fingerprint(context).equals(decoded[1])) {
        throw new IllegalArgumentException("continuation does not match this request");
      }
      var page = Integer.parseInt(decoded[2]);
      var offset = Integer.parseInt(decoded[3]);
      if (page < 1
          || page > MAX_PROVIDER_PAGE_NUMBER
          || offset < 0
          || offset >= PROVIDER_PAGE_SIZE) {
        throw new IllegalArgumentException("continuation position is invalid");
      }
      return new Position(page, offset);
    } catch (IllegalArgumentException e) {
      throw new CatalogException.BadRequest("continuation is invalid");
    }
  }

  private String encodePosition(String context, Position position) {
    var token = "v1:%s:%d:%d".formatted(fingerprint(context), position.page(), position.offset());
    return Base64.getUrlEncoder().withoutPadding().encodeToString(token.getBytes(UTF_8));
  }

  private String fingerprint(String context) {
    try {
      return HexFormat.of()
          .formatHex(MessageDigest.getInstance("SHA-256").digest(context.getBytes(UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private CatalogPage emptyPage() {
    return new CatalogPage(List.of(), null);
  }

  private void validateExternalId(String externalId) {
    if (externalId == null || !EXTERNAL_ID.matcher(externalId).matches()) {
      throw new CatalogException.BadRequest("external_id must be a valid Scryfall ID");
    }
  }

  private void validateFinish(String finish) {
    if (finish == null || !Games.get(GAME).supportsFinish(finish)) {
      throw new CatalogException.BadRequest("finish is unsupported for game " + GAME);
    }
  }

  private String scryfallFinish(String finish) {
    return "normal".equals(finish) ? "nonfoil" : finish;
  }

  private String escapeSearchPhrase(String query) {
    return query.replace("\\", "\\\\").replace("\"", "\\\"");
  }

  private String encode(String value) {
    return URLEncoder.encode(value, UTF_8).replace("+", "%20");
  }

  private @Nullable String imageUri(ScryfallCard card, String size) {
    var image = card.imageUris() != null ? card.imageUris().get(size) : null;
    if (image != null && image.isTextual()) {
      return image.asText();
    }
    if (card.cardFaces() == null || card.cardFaces().isEmpty()) {
      return null;
    }
    var faceImageUris = card.cardFaces().getFirst().imageUris();
    var faceImage = faceImageUris != null ? faceImageUris.get(size) : null;
    return faceImage != null && faceImage.isTextual() ? faceImage.asText() : null;
  }

  private static final class ScryfallNotFoundException extends RuntimeException {}
}
