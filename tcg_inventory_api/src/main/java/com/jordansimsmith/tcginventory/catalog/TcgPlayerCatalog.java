package com.jordansimsmith.tcginventory.catalog;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.jordansimsmith.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class TcgPlayerCatalog implements CardCatalog {
  private static final Duration REGISTRY_CHECK_INTERVAL = Duration.ofMinutes(5);
  private static final int PAGE_SIZE = 20;

  private final String game;
  private final CatalogRepository catalogRepository;
  private final CatalogSnapshotStore catalogSnapshotStore;
  private final Clock clock;

  private TcgCsvCatalogSnapshot snapshot;
  private Instant lastCheckedAt;

  public TcgPlayerCatalog(
      String game,
      CatalogRepository catalogRepository,
      CatalogSnapshotStore catalogSnapshotStore,
      Clock clock) {
    this.game = game;
    this.catalogRepository = catalogRepository;
    this.catalogSnapshotStore = catalogSnapshotStore;
    this.clock = clock;
  }

  @Override
  public CatalogCard.ImageUrls getImageUrls(String externalId) {
    return new CatalogCard.ImageUrls(
        "https://tcgplayer-cdn.tcgplayer.com/product/" + externalId + "_200w.jpg",
        "https://tcgplayer-cdn.tcgplayer.com/product/" + externalId + "_in_1000x1000.jpg");
  }

  @Override
  public CatalogCard getCard(String externalId) {
    var snapshot = load();
    var product =
        snapshot.products().stream()
            .filter(candidate -> Integer.toString(candidate.productId()).equals(externalId))
            .findFirst()
            .orElseThrow(() -> new CatalogException.NotFound("card not found"));
    return toCard(snapshot, product);
  }

  @Override
  public Map<String, CatalogCard> findCards(List<String> externalIds) {
    var requestedIds = new LinkedHashSet<>(externalIds);
    if (requestedIds.isEmpty()) {
      return Map.of();
    }

    var snapshot = load();
    var foundCards = new LinkedHashMap<String, CatalogCard>();
    for (var product : snapshot.products()) {
      var externalId = Integer.toString(product.productId());
      if (requestedIds.contains(externalId)) {
        foundCards.put(externalId, toCard(snapshot, product));
      }
    }
    return Map.copyOf(foundCards);
  }

  @Override
  public CatalogPage findAlternatives(String externalId, String finish, String continuation) {
    var snapshot = load();
    var offset = decodeContinuation(continuation);
    var anchor =
        snapshot.products().stream()
            .filter(product -> Integer.toString(product.productId()).equals(externalId))
            .findFirst()
            .orElseThrow(() -> new CatalogException.NotFound("card not found"));
    var products = new ArrayList<TcgCsvCatalogSnapshot.Product>();
    if (anchor.availableFinishes().contains(finish)) {
      products.add(anchor);
    }
    snapshot.products().stream()
        .filter(product -> product.productId() != anchor.productId())
        .filter(product -> product.name().equals(anchor.name()))
        .filter(product -> product.availableFinishes().contains(finish))
        .forEach(products::add);
    return page(snapshot, products, offset);
  }

  @Override
  public CatalogPage search(String rawQuery, String finish, String continuation) {
    var query = rawQuery.trim();
    if (query.length() < 2 || query.length() > 200) {
      throw new CatalogException.BadRequest("query must contain between 2 and 200 characters");
    }
    var normalizedQuery = query.toLowerCase(Locale.ROOT);
    var snapshot = load();
    var offset = decodeContinuation(continuation);
    var products =
        snapshot.products().stream()
            .filter(
                product -> product.name().trim().toLowerCase(Locale.ROOT).contains(normalizedQuery))
            .filter(product -> product.availableFinishes().contains(finish))
            .toList();
    return page(snapshot, products, offset);
  }

  private CatalogPage page(
      TcgCsvCatalogSnapshot snapshot, List<TcgCsvCatalogSnapshot.Product> products, int offset) {
    if (offset > 0 && offset >= products.size()) {
      throw new CatalogException.BadRequest("continuation position is invalid");
    }
    var end = Math.min(offset + PAGE_SIZE, products.size());
    var cards =
        products.subList(offset, end).stream().map(product -> toCard(snapshot, product)).toList();
    var next =
        end < products.size()
            ? Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(Integer.toString(end).getBytes(UTF_8))
            : null;
    return new CatalogPage(cards, next);
  }

  private int decodeContinuation(String token) {
    if (token == null) {
      return 0;
    }
    try {
      var offset = Integer.parseInt(new String(Base64.getUrlDecoder().decode(token), UTF_8));
      if (offset <= 0) {
        throw new IllegalArgumentException("continuation position is invalid");
      }
      return offset;
    } catch (IllegalArgumentException e) {
      throw new CatalogException.BadRequest("continuation is invalid");
    }
  }

  private synchronized TcgCsvCatalogSnapshot load() {
    var now = clock.now();
    if (snapshot != null && now.isBefore(lastCheckedAt.plus(REGISTRY_CHECK_INTERVAL))) {
      return snapshot;
    }

    try {
      var latestSnapshot = catalogRepository.getLatestSnapshot(game);
      if (latestSnapshot == null) {
        throw new CatalogException.Unavailable("catalog has not been published for game: " + game);
      }
      if (snapshot != null && snapshot.snapshotId().equals(latestSnapshot.getSnapshotId())) {
        lastCheckedAt = clock.now();
        return snapshot;
      }

      var nextSnapshot = catalogSnapshotStore.getSnapshot(latestSnapshot);
      snapshot = nextSnapshot;
      lastCheckedAt = clock.now();
      return nextSnapshot;
    } catch (CatalogException e) {
      throw e;
    } catch (Exception e) {
      if (e instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      throw new CatalogException.Unavailable("catalog is temporarily unavailable", e);
    }
  }

  private CatalogCard toCard(
      TcgCsvCatalogSnapshot snapshot, TcgCsvCatalogSnapshot.Product product) {
    var group =
        snapshot.groups().stream()
            .filter(candidate -> candidate.groupId() == product.groupId())
            .findFirst()
            .orElseThrow();
    var productId = Integer.toString(product.productId());
    return new CatalogCard(
        game,
        productId,
        product.name(),
        group.setCode(),
        group.setName(),
        product.collectorNumber(),
        getImageUrls(productId),
        product.availableFinishes());
  }
}
