package com.jordansimsmith.tcginventory.catalog;

import com.jordansimsmith.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

public class TcgPlayerCatalog implements CardCatalog {
  private static final Duration REGISTRY_CHECK_INTERVAL = Duration.ofMinutes(5);

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
    throw new CatalogException.BadRequest("catalog review is unavailable for game: " + game);
  }

  @Override
  public CatalogPage search(String query, String finish, String continuation) {
    throw new CatalogException.BadRequest("catalog review is unavailable for game: " + game);
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
