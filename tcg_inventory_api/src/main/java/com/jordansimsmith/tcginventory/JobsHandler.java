package com.jordansimsmith.tcginventory;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.annotations.VisibleForTesting;
import com.jordansimsmith.queue.QueueClient;
import com.jordansimsmith.service.ServiceException;
import com.jordansimsmith.service.ServiceUnavailableException;
import com.jordansimsmith.tcginventory.catalog.CatalogFactory;
import com.jordansimsmith.tcginventory.scans.ScanConfirmationJobProcessor;
import com.jordansimsmith.tcginventory.scans.ScanItem;
import com.jordansimsmith.tcginventory.scans.ScanRepository;
import com.jordansimsmith.tcginventory.scans.ScanRowItem;
import com.jordansimsmith.time.Clock;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;

public class JobsHandler implements RequestHandler<SQSEvent, Void> {

  private static final Logger LOGGER = LoggerFactory.getLogger(JobsHandler.class);
  private final ObjectMapper objectMapper;
  private final Clock clock;
  private final DynamoDbTable<JobItem> jobTable;
  private final QueueClient<JobMessage> jobsQueue;
  private final JobProcessor scanConfirmationJobProcessor;

  public JobsHandler() {
    this(CatalogFactory.create());
  }

  @VisibleForTesting
  public JobsHandler(CatalogFactory factory) {
    this.objectMapper = factory.objectMapper();
    this.clock = factory.clock();
    this.jobTable = factory.jobTable();
    this.jobsQueue = factory.jobsQueue();
    var enhancedClient = factory.dynamoDbEnhancedClient();
    this.scanConfirmationJobProcessor =
        new ScanConfirmationJobProcessor(
            new ScanRepository(
                TcgInventoryTable.table(enhancedClient, ScanItem.class),
                TcgInventoryTable.table(enhancedClient, ScanRowItem.class),
                factory.dynamoDbClient(),
                factory.clock()),
            factory.catalogs(),
            factory.gamesService(),
            factory.importService());
  }

  @Override
  public Void handleRequest(SQSEvent event, Context context) {
    doHandleRequest(event);
    return null;
  }

  private void doHandleRequest(SQSEvent event) {
    var record = event.getRecords().get(0);
    var message = readMessage(record.getBody());

    var jobKey =
        Key.builder()
            .partitionValue(JobItem.formatPk(message.user()))
            .sortValue(JobItem.formatSk(message.jobId()))
            .build();

    var jobItem = jobTable.getItem(request -> request.key(jobKey).consistentRead(true));
    if (jobItem == null) {
      throw new IllegalStateException("job item not found: " + message.jobId());
    }

    var status = jobItem.getStatus();
    if ("succeeded".equals(status) || "failed".equals(status)) {
      LOGGER.info("duplicate delivery for completed job: {} ({})", message.jobId(), status);
      return;
    }

    var processor = jobProcessor(message.jobType());

    if ("queued".equals(status)) {
      jobItem.setStatus("running");
      jobItem.setProcessedCount(0);
      jobItem.setUpdatedAt(clock.now());
      jobTable.updateItem(jobItem);
    }

    JobProcessor.JobResult result;
    try {
      result = processor.processBatch(message.user(), jobItem);
    } catch (ServiceUnavailableException e) {
      throw new RuntimeException("service is temporarily unavailable", e);
    } catch (ServiceException e) {
      jobItem.setStatus("failed");
      jobItem.setError(e.getMessage());
      jobItem.setUpdatedAt(clock.now());
      jobTable.updateItem(jobItem);
      return;
    }
    if (result instanceof JobProcessor.FailureJobResult failure) {
      jobItem.setStatus("failed");
      jobItem.setError(failure.error());
      jobItem.setUpdatedAt(clock.now());
      jobTable.updateItem(jobItem);
      return;
    }

    var batchResult = (JobProcessor.SuccessJobResult) result;
    processBatch(message, jobItem, batchResult);
  }

  private JobMessage readMessage(String body) {
    try {
      return objectMapper.readValue(body, JobMessage.class);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  private void processBatch(
      JobMessage message, JobItem jobItem, JobProcessor.SuccessJobResult result) {
    var previousProcessedCount = jobItem.getProcessedCount();

    // the processed count deduplication id only distinguishes batches when every
    // re-enqueueing batch advances; a non-advancing batch would be silently
    // deduplicated into a stalled job, so fail loudly instead
    if (result.continuation() != null && result.processedCount() <= previousProcessedCount) {
      throw new IllegalStateException(
          "job processed count did not advance: "
              + previousProcessedCount
              + " -> "
              + result.processedCount());
    }

    jobItem.setContinuation(result.continuation());
    jobItem.setProcessedCount(result.processedCount());
    jobItem.setUpdatedAt(clock.now());

    if (result.continuation() == null) {
      jobItem.setStatus("succeeded");
    }
    jobTable.updateItem(jobItem);

    if (result.continuation() != null) {
      jobsQueue.send(message, message.user(), message.deduplicationId(result.processedCount()));
    }
  }

  private JobProcessor jobProcessor(String jobType) {
    return switch (jobType) {
      case "scan_confirmation" -> scanConfirmationJobProcessor;
      default -> throw new IllegalArgumentException("unknown job type: " + jobType);
    };
  }
}
