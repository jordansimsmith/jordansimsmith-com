package com.jordansimsmith.tcginventory;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.annotations.VisibleForTesting;
import com.jordansimsmith.queue.QueueClient;
import com.jordansimsmith.tcginventory.imports.AppraiseJobProcessor;
import com.jordansimsmith.tcginventory.imports.ImportItem;
import com.jordansimsmith.tcginventory.imports.ImportRowItem;
import com.jordansimsmith.tcginventory.inventory.InventoryRepository;
import com.jordansimsmith.tcginventory.inventory.SkuItem;
import com.jordansimsmith.tcginventory.inventory.UnitItem;
import com.jordansimsmith.tcginventory.orders.OrderItem;
import com.jordansimsmith.tcginventory.orders.OrderPhaseProcessor;
import com.jordansimsmith.tcginventory.orders.OrderRepository;
import com.jordansimsmith.tcginventory.publish.ListingPhaseProcessor;
import com.jordansimsmith.tcginventory.publish.PublishJobProcessor;
import com.jordansimsmith.tcginventory.reports.ReportItem;
import com.jordansimsmith.tcginventory.reports.ReportJobProcessor;
import com.jordansimsmith.tcginventory.settings.SettingsItem;
import com.jordansimsmith.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;

public class JobsHandler implements RequestHandler<SQSEvent, Void> {

  private static final Logger LOGGER = LoggerFactory.getLogger(JobsHandler.class);
  private static final int MAX_ERROR_LENGTH = 300;

  private final ObjectMapper objectMapper;
  private final Clock clock;
  private final DynamoDbTable<JobItem> jobTable;
  private final DynamoDbTable<ImportItem> importTable;
  private final QueueClient<JobMessage> jobsQueue;
  private final AppraiseJobProcessor appraiseJobProcessor;
  private final PublishJobProcessor publishJobProcessor;
  private final ReportJobProcessor reportJobProcessor;

  public JobsHandler() {
    this(TcgInventoryFactory.create());
  }

  @VisibleForTesting
  public JobsHandler(TcgInventoryFactory factory) {
    this.objectMapper = factory.objectMapper();
    this.clock = factory.clock();
    this.jobTable = factory.jobTable();
    this.importTable = TcgInventoryTable.table(factory.dynamoDbEnhancedClient(), ImportItem.class);
    var importRowTable =
        TcgInventoryTable.table(factory.dynamoDbEnhancedClient(), ImportRowItem.class);
    var unitTable = TcgInventoryTable.table(factory.dynamoDbEnhancedClient(), UnitItem.class);
    var inventoryRepository =
        new InventoryRepository(
            unitTable, factory.dynamoDbClient(), factory.clock(), factory.ulidGenerator());
    var orderTable = TcgInventoryTable.table(factory.dynamoDbEnhancedClient(), OrderItem.class);
    var orderRepository =
        new OrderRepository(
            orderTable, inventoryRepository, factory.dynamoDbClient(), factory.clock());
    var skuTable = TcgInventoryTable.table(factory.dynamoDbEnhancedClient(), SkuItem.class);
    var reportTable = TcgInventoryTable.table(factory.dynamoDbEnhancedClient(), ReportItem.class);
    this.jobsQueue = factory.jobsQueue();
    this.appraiseJobProcessor =
        new AppraiseJobProcessor(
            importTable, importRowTable, factory.clock(), factory.fetchTcgClient());
    this.publishJobProcessor =
        new PublishJobProcessor(
            factory.fetchTcgTokenMinter(),
            new OrderPhaseProcessor(
                orderTable,
                skuTable,
                TcgInventoryTable.table(factory.dynamoDbEnhancedClient(), SettingsItem.class),
                orderRepository,
                factory.clock(),
                factory.fetchTcgClient()),
            new ListingPhaseProcessor(
                skuTable,
                inventoryRepository,
                factory.dynamoDbClient(),
                factory.clock(),
                factory.fetchTcgClient(),
                factory.s3Client()));
    this.reportJobProcessor =
        new ReportJobProcessor(
            reportTable,
            inventoryRepository,
            factory.auditTable(),
            skuTable,
            orderTable,
            factory.objectMapper(),
            factory.clock());
  }

  @Override
  public Void handleRequest(SQSEvent event, Context context) {
    try {
      doHandleRequest(event);
    } catch (Exception e) {
      LOGGER.error("error processing job", e);
      throw new RuntimeException(e);
    }
    return null;
  }

  private void doHandleRequest(SQSEvent event) throws Exception {
    var record = event.getRecords().get(0);
    var message = objectMapper.readValue(record.getBody(), JobMessage.class);

    var jobKey =
        Key.builder()
            .partitionValue(JobItem.formatPk(message.user()))
            .sortValue(JobItem.formatSk(message.jobId()))
            .build();

    var jobItem = jobTable.getItem(jobKey);
    if (jobItem == null) {
      LOGGER.warn("job item not found: {}", message.jobId());
      return;
    }

    var status = jobItem.getStatus();
    if ("succeeded".equals(status) || "failed".equals(status)) {
      LOGGER.info("duplicate delivery for completed job: {} ({})", message.jobId(), status);
      return;
    }

    if ("queued".equals(status)) {
      jobItem.setStatus("running");
      jobItem.setProcessedCount(0);
      jobItem.setUpdatedAt(clock.now());
      jobTable.putItem(jobItem);
    }

    try {
      processBatch(message, jobItem);
    } catch (Exception e) {
      LOGGER.error("job processing failed: {}", message.jobId(), e);
      var error = summarizeError(e);
      jobItem.setStatus("failed");
      jobItem.setError(error);
      jobItem.setUpdatedAt(clock.now());
      jobTable.putItem(jobItem);

      if ("appraise".equals(message.jobType()) && jobItem.getImportId() != null) {
        setImportError(message.user(), jobItem.getImportId(), error);
      }
      return;
    }
  }

  // the full exception is already in the logs; store a short actionable summary for the ui
  private String summarizeError(Exception e) {
    for (Throwable t = e; t != null; t = t.getCause()) {
      if (t instanceof FetchTcgAuthException) {
        return "FetchTCG authentication failed. Replace the refresh token in settings.";
      }
    }

    Throwable rootCause = e;
    while (rootCause.getCause() != null) {
      rootCause = rootCause.getCause();
    }
    var errorMessage =
        rootCause.getMessage() != null
            ? rootCause.getMessage()
            : rootCause.getClass().getSimpleName();
    if (errorMessage.length() > MAX_ERROR_LENGTH) {
      return errorMessage.substring(0, MAX_ERROR_LENGTH) + "…";
    }
    return errorMessage;
  }

  private void processBatch(JobMessage message, JobItem jobItem) {
    var previousContinuation = jobItem.getContinuation() != null ? jobItem.getContinuation() : 0;

    var result =
        switch (message.jobType()) {
          case "appraise" -> appraiseJobProcessor.processBatch(message.user(), jobItem);
          case "publish" -> publishJobProcessor.processBatch(message.user(), jobItem);
          case "report" -> reportJobProcessor.processBatch(message.user(), jobItem);
          default -> throw new IllegalArgumentException("unknown job type: " + message.jobType());
        };

    // the continuation deduplication id only distinguishes slices when every
    // re-enqueueing slice advances; a non-advancing slice would be silently
    // deduplicated into a stalled job, so fail loudly instead
    if (!result.complete() && result.processedUpTo() <= previousContinuation) {
      throw new IllegalStateException(
          "job continuation did not advance: "
              + previousContinuation
              + " -> "
              + result.processedUpTo());
    }

    jobItem.setContinuation(result.processedUpTo());
    jobItem.setProcessedCount(result.processedUpTo());
    jobItem.setUpdatedAt(clock.now());

    if (result.complete()) {
      jobItem.setStatus("succeeded");
    }
    jobTable.putItem(jobItem);

    if (!result.complete()) {
      jobsQueue.send(message, message.user(), message.deduplicationId(result.processedUpTo()));
    }
  }

  private void setImportError(String user, String importId, String error) {
    var importKey =
        Key.builder()
            .partitionValue(ImportItem.formatPk(user))
            .sortValue(ImportItem.formatSk(importId))
            .build();
    var importItem = importTable.getItem(importKey);
    if (importItem != null) {
      importItem.setError(error);
      importItem.setUpdatedAt(clock.now());
      importTable.putItem(importItem);
    }
  }
}
