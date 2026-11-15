package com.jordansimsmith.tcginventory.catalog.server;

import static java.nio.charset.StandardCharsets.UTF_8;

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
import com.jordansimsmith.time.Clock;
import java.io.IOException;
import java.net.URLEncoder;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;
import software.amazon.awssdk.core.exception.SdkException;

public class PokemonPrintingLookup {
  private static final Duration REGISTRY_CHECK_INTERVAL = Duration.ofMinutes(5);
  private static final String UNAVAILABLE = "catalog is temporarily unavailable";

  private final CatalogRepository repository;
  private final CatalogSnapshotStore snapshotStore;
  private final Clock clock;
  private Map<String, CatalogPrinting> cachedPrintings;
  private String snapshotId;
  private Instant lastCheckedAt;

  public PokemonPrintingLookup(
      CatalogRepository repository, CatalogSnapshotStore snapshotStore, Clock clock) {
    this.repository = repository;
    this.snapshotStore = snapshotStore;
    this.clock = clock;
  }

  public static ImageUrls getCardImages(String externalId) {
    var encodedExternalId = URLEncoder.encode(externalId, UTF_8).replace("+", "%20");
    return new ImageUrls(
        "https://tcgplayer-cdn.tcgplayer.com/product/" + encodedExternalId + "_200w.jpg",
        "https://tcgplayer-cdn.tcgplayer.com/product/" + encodedExternalId + "_in_1000x1000.jpg");
  }

  public Card getCard(Game game, String externalId) throws ServiceException {
    return toCard(getPrinting(loadPrintings(game), externalId));
  }

  public List<CatalogPrinting> findCards(Game game, Set<String> externalIds)
      throws ServiceUnavailableException {
    var printings = loadPrintings(game);
    var cards = new ArrayList<CatalogPrinting>();
    for (var externalId : externalIds) {
      var printing = printings.get(externalId);
      if (printing != null && !printing.availableFinishes().isEmpty()) {
        cards.add(printing);
      }
    }
    return List.copyOf(cards);
  }

  public SearchCardsResponse searchCards(
      Game game, String query, String finish, int limit, @Nullable String continuation)
      throws ServiceException {
    var printings = loadPrintings(game);
    var normalizedQuery = query.toLowerCase(Locale.ROOT);
    var matches =
        printings.values().stream()
            .filter(printing -> printing.availableFinishes().contains(finish))
            .filter(
                printing ->
                    printing.name().trim().toLowerCase(Locale.ROOT).contains(normalizedQuery))
            .toList();
    return page(matches, limit, continuation);
  }

  public FindCardAlternativesResponse findCardAlternatives(
      Game game, String externalId, String finish, int limit, @Nullable String continuation)
      throws ServiceException {
    var printings = loadPrintings(game);
    var anchor = getPrinting(printings, externalId);
    var matches = new ArrayList<CatalogPrinting>();
    if (anchor.availableFinishes().contains(finish)) {
      matches.add(anchor);
    }
    for (var printing : printings.values()) {
      if (!externalId.equals(printing.externalId())
          && anchor.name().equals(printing.name())
          && printing.availableFinishes().contains(finish)) {
        matches.add(printing);
      }
    }

    var page = page(matches, limit, continuation);
    return new FindCardAlternativesResponse(page.cards(), page.nextContinuation());
  }

  private CatalogPrinting getPrinting(Map<String, CatalogPrinting> printings, String externalId)
      throws NotFoundException {
    var printing = printings.get(externalId);
    if (printing == null) {
      throw new NotFoundException("card not found");
    }
    return printing;
  }

  private SearchCardsResponse page(
      List<CatalogPrinting> matches, int limit, @Nullable String continuation)
      throws BadRequestException {
    var offset = decodeContinuation(continuation);
    if (offset > 0 && offset >= matches.size()) {
      throw new BadRequestException("continuation position is invalid");
    }
    var end = Math.min(offset + limit, matches.size());
    var cards = matches.subList(offset, end).stream().map(this::toCard).toList();
    return new SearchCardsResponse(cards, end < matches.size() ? encodeContinuation(end) : null);
  }

  private String encodeContinuation(int offset) {
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(Integer.toString(offset).getBytes(UTF_8));
  }

  private int decodeContinuation(@Nullable String continuation) throws BadRequestException {
    if (continuation == null) {
      return 0;
    }
    try {
      var offset = Integer.parseInt(new String(Base64.getUrlDecoder().decode(continuation), UTF_8));
      if (offset <= 0) {
        throw new BadRequestException("continuation is invalid");
      }
      return offset;
    } catch (IllegalArgumentException e) {
      throw new BadRequestException("continuation is invalid");
    }
  }

  private Card toCard(CatalogPrinting printing) {
    return new Card(
        printing.game(),
        printing.externalId(),
        printing.name(),
        printing.setCode(),
        printing.setName(),
        printing.collectorNumber(),
        getCardImages(printing.externalId()),
        printing.availableFinishes());
  }

  private synchronized Map<String, CatalogPrinting> loadPrintings(Game game)
      throws ServiceUnavailableException {
    if (cachedPrintings != null
        && clock.now().isBefore(lastCheckedAt.plus(REGISTRY_CHECK_INTERVAL))) {
      return cachedPrintings;
    }

    try {
      var latest = repository.getLatestSnapshot(game.id());
      if (latest == null || latest.getSnapshotId() == null || latest.getSnapshotId().isBlank()) {
        throw new ServiceUnavailableException(UNAVAILABLE);
      }

      var latestId = latest.getSnapshotId();
      if (!latestId.equals(snapshotId)) {
        if (latest.getS3Key() == null || latest.getS3Key().isBlank()) {
          throw new ServiceUnavailableException(UNAVAILABLE);
        }
        cachedPrintings = toPrintingIndex(game, snapshotStore.getSnapshot(latest));
        snapshotId = latestId;
      }
      lastCheckedAt = clock.now();
      return cachedPrintings;
    } catch (IOException | SdkException | IllegalArgumentException e) {
      throw new ServiceUnavailableException(UNAVAILABLE, e);
    }
  }

  private Map<String, CatalogPrinting> toPrintingIndex(Game game, TcgCsvCatalogSnapshot snapshot)
      throws ServiceUnavailableException {
    if (snapshot == null || !game.id().equals(snapshot.game())) {
      throw new ServiceUnavailableException(UNAVAILABLE);
    }

    var groups = new HashMap<Integer, TcgCsvCatalogSnapshot.Group>();
    for (var group : snapshot.groups()) {
      if (group.setCode() == null
          || group.setName() == null
          || groups.putIfAbsent(group.groupId(), group) != null) {
        throw new ServiceUnavailableException(UNAVAILABLE);
      }
    }

    var sortedProducts = new ArrayList<>(snapshot.products());
    sortedProducts.sort(Comparator.comparingInt(TcgCsvCatalogSnapshot.Product::productId));
    var gameFinishes = game.finishes().stream().map(Finish::id).toList();
    var printings = new LinkedHashMap<String, CatalogPrinting>();
    for (var product : sortedProducts) {
      var group = groups.get(product.groupId());
      if (group == null || product.name() == null || product.collectorNumber() == null) {
        throw new ServiceUnavailableException(UNAVAILABLE);
      }
      var externalId = Integer.toString(product.productId());
      var finishes = gameFinishes.stream().filter(product.availableFinishes()::contains).toList();
      var printing =
          new CatalogPrinting(
              game.id(),
              externalId,
              product.name(),
              group.setCode(),
              group.setName(),
              product.collectorNumber(),
              finishes);
      if (printings.putIfAbsent(externalId, printing) != null) {
        throw new ServiceUnavailableException(UNAVAILABLE);
      }
    }
    return Collections.unmodifiableMap(printings);
  }
}
