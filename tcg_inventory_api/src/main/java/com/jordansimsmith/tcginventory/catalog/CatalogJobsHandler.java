package com.jordansimsmith.tcginventory.catalog;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.annotations.VisibleForTesting;
import com.jordansimsmith.tcginventory.TcgInventoryTable;
import java.io.IOException;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;

public class CatalogJobsHandler implements RequestHandler<SQSEvent, Void> {
  private static final Logger LOGGER = LoggerFactory.getLogger(CatalogJobsHandler.class);

  private final ObjectMapper objectMapper;
  private final CatalogRefreshProcessor catalogRefreshProcessor;

  public CatalogJobsHandler() {
    this(CatalogFactory.create());
  }

  @VisibleForTesting
  CatalogJobsHandler(CatalogFactory factory) {
    this.objectMapper = factory.objectMapper();
    var catalogSnapshotTable =
        factory
            .dynamoDbEnhancedClient()
            .table(TcgInventoryTable.TABLE_NAME, TableSchema.fromBean(CatalogSnapshotItem.class));
    var catalogRepository = new CatalogRepository(catalogSnapshotTable, factory.dynamoDbClient());
    var catalogSnapshotStore =
        new CatalogSnapshotStore(
            factory.s3Client(),
            CatalogSnapshotStore.BUCKET,
            catalogRepository,
            new TcgCsvCatalogArtifactCodec(factory.objectMapper()));
    this.catalogRefreshProcessor =
        new CatalogRefreshProcessor(
            factory.tcgCsvClient(),
            new TcgCsvCatalogNormalizer(),
            catalogSnapshotStore,
            catalogRepository,
            factory.clock());
  }

  @Override
  public Void handleRequest(SQSEvent event, Context context) {
    try {
      doHandleRequest(event);
      return null;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      LOGGER.error("catalog refresh was interrupted", e);
      throw new RuntimeException(e);
    } catch (Exception e) {
      LOGGER.error("catalog refresh failed", e);
      throw new RuntimeException(e);
    }
  }

  private void doHandleRequest(SQSEvent event) throws IOException, InterruptedException {
    if (event.getRecords().size() != 1) {
      throw new IllegalArgumentException(
          "expected one SQS record, got " + event.getRecords().size());
    }

    var message =
        objectMapper.readValue(
            event.getRecords().getFirst().getBody(), CatalogRefreshMessage.class);
    if (message.scheduledAt() == null || message.scheduledAt().isBlank()) {
      throw new IllegalArgumentException("catalog refresh message is missing scheduled_at");
    }
    if (!message.scheduledAt().endsWith("Z")) {
      throw new IllegalArgumentException("scheduled_at must be a UTC timestamp");
    }
    Instant scheduledAt;
    try {
      scheduledAt = Instant.parse(message.scheduledAt());
    } catch (RuntimeException e) {
      throw new IllegalArgumentException("invalid scheduled_at: " + message.scheduledAt(), e);
    }
    catalogRefreshProcessor.process(message.game(), scheduledAt);
  }
}
