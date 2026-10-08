package com.jordansimsmith.tcginventory.catalog;

import com.jordansimsmith.time.Clock;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CatalogRefreshProcessor {
  private static final Logger LOGGER = LoggerFactory.getLogger(CatalogRefreshProcessor.class);
  private static final int CATEGORY_ID = 3;
  private static final String GAME = "pokemon";

  private final TcgCsvClient tcgCsvClient;
  private final TcgCsvCatalogNormalizer normalizer;
  private final CatalogSnapshotStore catalogSnapshotStore;
  private final CatalogRepository catalogRepository;
  private final Clock clock;

  public CatalogRefreshProcessor(
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

  public void process(String game, Instant scheduledAt) throws IOException, InterruptedException {
    if (!GAME.equals(game)) {
      throw new IllegalArgumentException("unsupported catalog game: " + game);
    }

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

    var groups = tcgCsvClient.findGroups(CATEGORY_ID);
    var groupSources = new ArrayList<TcgCsvCatalogNormalizer.GroupSource>();
    var rawProductCount = 0;
    for (var group : groups) {
      var products = tcgCsvClient.findProducts(CATEGORY_ID, group.groupId());
      var prices = tcgCsvClient.findPrices(CATEGORY_ID, group.groupId());
      rawProductCount += products.size();
      groupSources.add(new TcgCsvCatalogNormalizer.GroupSource(group, products, prices));
    }

    var snapshot = normalizer.normalize(marker, clock.now(), groups, groupSources);
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
