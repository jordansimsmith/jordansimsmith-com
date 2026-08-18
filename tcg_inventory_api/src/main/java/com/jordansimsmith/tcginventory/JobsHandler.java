package com.jordansimsmith.tcginventory;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.annotations.VisibleForTesting;
import com.jordansimsmith.queue.QueueClient;
import com.jordansimsmith.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;

public class JobsHandler implements RequestHandler<SQSEvent, Void> {

  private static final Logger LOGGER = LoggerFactory.getLogger(JobsHandler.class);

  private final ObjectMapper objectMapper;
  private final Clock clock;
  private final DynamoDbTable<TcgInventoryItem> tcgInventoryTable;
  private final QueueClient<JobMessage> jobsQueue;
  private final AppraiseJobProcessor appraiseJobProcessor;
  private final PublishJobProcessor publishJobProcessor;
  private final ReportJobProcessor reportJobProcessor;

  public JobsHandler() {
    this(TcgInventoryFactory.create());
  }

  @VisibleForTesting
  JobsHandler(TcgInventoryFactory factory) {
    this.objectMapper = factory.objectMapper();
    this.clock = factory.clock();
    this.tcgInventoryTable = factory.tcgInventoryTable();
    this.jobsQueue = factory.jobsQueue();
    this.appraiseJobProcessor =
        new AppraiseJobProcessor(
            factory.tcgInventoryTable(), factory.clock(), factory.fetchTcgClient());
    this.publishJobProcessor =
        new PublishJobProcessor(
            factory.fetchTcgTokenMinter(),
            new OrderPhaseProcessor(
                factory.tcgInventoryTable(),
                factory.dynamoDbClient(),
                factory.clock(),
                factory.ulidGenerator(),
                factory.fetchTcgClient(),
                factory.objectMapper()),
            new ListingPhaseProcessor(
                factory.tcgInventoryTable(),
                factory.dynamoDbClient(),
                factory.clock(),
                factory.fetchTcgClient()));
    this.reportJobProcessor = new ReportJobProcessor(factory.tcgInventoryTable(), factory.clock());
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
            .partitionValue(TcgInventoryItem.formatUserPk(message.user()))
            .sortValue(TcgInventoryItem.formatJobSk(message.jobId()))
            .build();

    var jobItem = tcgInventoryTable.getItem(jobKey);
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
      tcgInventoryTable.putItem(jobItem);
    }

    try {
      processBatch(message, jobItem);
    } catch (Exception e) {
      LOGGER.error("job processing failed: {}", message.jobId(), e);
      jobItem.setStatus("failed");
      jobItem.setError(e.getMessage());
      jobItem.setUpdatedAt(clock.now());
      tcgInventoryTable.putItem(jobItem);

      if ("appraise".equals(message.jobType()) && jobItem.getImportId() != null) {
        setImportError(message.user(), jobItem.getImportId(), e.getMessage());
      }
      return;
    }
  }

  private void processBatch(JobMessage message, TcgInventoryItem jobItem) {
    var result =
        switch (message.jobType()) {
          case "appraise" -> appraiseJobProcessor.processBatch(message.user(), jobItem);
          case "publish" -> publishJobProcessor.processBatch(message.user(), jobItem);
          case "report" -> reportJobProcessor.processBatch(message.user(), jobItem);
          default -> throw new IllegalArgumentException("unknown job type: " + message.jobType());
        };

    jobItem.setContinuation(result.processedUpTo());
    jobItem.setProcessedCount(result.processedUpTo());
    jobItem.setUpdatedAt(clock.now());

    if (result.complete()) {
      jobItem.setStatus("succeeded");
    }
    tcgInventoryTable.putItem(jobItem);

    if (!result.complete()) {
      jobsQueue.send(message, message.user());
    }
  }

  private void setImportError(String user, String importId, String error) {
    var importKey =
        Key.builder()
            .partitionValue(TcgInventoryItem.formatUserPk(user))
            .sortValue(TcgInventoryItem.formatImportSk(importId))
            .build();
    var importItem = tcgInventoryTable.getItem(importKey);
    if (importItem != null) {
      importItem.setError(error);
      importItem.setUpdatedAt(clock.now());
      tcgInventoryTable.putItem(importItem);
    }
  }
}
