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
import com.jordansimsmith.tcginventory.catalog.client.ImageUrls;
import com.jordansimsmith.tcginventory.games.client.Finish;
import com.jordansimsmith.tcginventory.games.client.Game;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

public class MagicTheGatheringPrintingLookup {
  private static final int COLLECTION_REQUEST_SIZE = 75;
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
      @JsonProperty("card_faces") List<ScryfallCardFace> cardFaces) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  private record ScryfallImageUris(
      @JsonProperty("small") String small, @JsonProperty("normal") String normal) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  private record ScryfallCardFace(@JsonProperty("image_uris") ScryfallImageUris imageUris) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  private record Identifier(@JsonProperty("id") String id) {}

  private record CollectionRequest(@JsonProperty("identifiers") List<Identifier> identifiers) {}

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
