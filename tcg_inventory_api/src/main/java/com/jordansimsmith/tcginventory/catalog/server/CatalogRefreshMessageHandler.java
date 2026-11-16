package com.jordansimsmith.tcginventory.catalog.server;

import com.jordansimsmith.time.Clock;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CatalogRefreshMessageHandler {
  private static final Logger LOGGER = LoggerFactory.getLogger(CatalogRefreshMessageHandler.class);

  private final TcgCsvClient tcgCsvClient;
  private final TcgCsvCatalogNormalizer normalizer;
  private final CatalogSnapshotStore catalogSnapshotStore;
  private final CatalogRepository catalogRepository;
  private final Clock clock;

  public CatalogRefreshMessageHandler(
      TcgCsvClient tcgCsvClient,
      TcgCsvCatalogNormalizer normalizer,
      CatalogSnapshotStore catalogSnapshotStore,
      CatalogRepository catalogRepository,
      Clock clock) {
    this.tcgCsvClient = tcgCsvClient;
    this.normalizer = normalizer;
    this.catalogSnapshotStore = catalogSnapshotStore;
    this.catalogRepository = catalogRepository;
    this.clock = clock;
  }

  public void handleMessage(CatalogRefreshMessage message)
      throws IOException, InterruptedException {
    if (message.scheduledAt() == null || message.scheduledAt().isBlank()) {
      throw new IllegalArgumentException("catalog refresh message is missing scheduled_at");
    }
    if (!message.scheduledAt().endsWith("Z")) {
      throw new IllegalArgumentException("scheduled_at must be a UTC timestamp");
    }
    Instant scheduledAt;
    try {
      scheduledAt = Instant.parse(message.scheduledAt());
    } catch (DateTimeParseException e) {
      throw new IllegalArgumentException("invalid scheduled_at: " + message.scheduledAt(), e);
    }
    var definition = TcgCsvCatalogDefinition.forGame(message.game());
    var game = definition.gameId();
    var categoryId = definition.categoryId();

    var startedAt = clock.now();
    var marker = tcgCsvClient.getUpdateMarker();
    var latest = catalogRepository.getLatestSnapshot(game);
    if (latest != null && !latest.getSourceUpdatedAt().isBefore(marker.updatedAt())) {
      LOGGER.info(
          "catalog refresh outcome=skipped game={} scheduled_at={} source_updated_at={}"
              + " latest_snapshot_id={} duration_seconds={}",
          game,
          scheduledAt,
          marker.updatedAt(),
          latest.getSnapshotId(),
          Duration.between(startedAt, clock.now()).toSeconds());
      return;
    }

    var groups = tcgCsvClient.findGroups(categoryId);
    var groupSources = new ArrayList<TcgCsvCatalogNormalizer.GroupSource>();
    var rawProductCount = 0;
    for (var group : groups) {
      var products = tcgCsvClient.findProducts(categoryId, group.groupId());
      var prices = tcgCsvClient.findPrices(categoryId, group.groupId());
      rawProductCount += products.size();
      groupSources.add(new TcgCsvCatalogNormalizer.GroupSource(group, products, prices));
    }

    var snapshot = normalizer.normalize(definition, marker, clock.now(), groups, groupSources);
    var currentMarker = tcgCsvClient.getUpdateMarker();
    if (!marker.updatedAt().equals(currentMarker.updatedAt())) {
      throw new IllegalStateException(
          "TCGCSV source changed during catalog refresh: "
              + marker.updatedAt()
              + " -> "
              + currentMarker.updatedAt());
    }

    catalogSnapshotStore.createSnapshot(snapshot);
    LOGGER.info(
        "catalog refresh outcome=published game={} scheduled_at={} snapshot_id={}"
            + " source_updated_at={} source_age_seconds={} groups={} raw_products={}"
            + " included_products={} duration_seconds={}",
        game,
        scheduledAt,
        snapshot.snapshotId(),
        marker.updatedAt(),
        Duration.between(Instant.ofEpochSecond(snapshot.sourceUpdatedAt()), clock.now())
            .toSeconds(),
        snapshot.groups().size(),
        rawProductCount,
        snapshot.products().size(),
        Duration.between(startedAt, clock.now()).toSeconds());
  }
}
