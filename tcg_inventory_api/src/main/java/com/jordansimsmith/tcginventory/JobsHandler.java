package com.jordansimsmith.tcginventory;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.annotations.VisibleForTesting;
import com.jordansimsmith.queue.QueueClient;
import com.jordansimsmith.tcginventory.imports.AppraiseJobProcessor;
import com.jordansimsmith.tcginventory.publish.PublishJobProcessor;
import com.jordansimsmith.tcginventory.reports.ReportJobProcessor;
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
  private final JobProcessor appraiseJobProcessor;
  private final JobProcessor publishJobProcessor;
  private final JobProcessor reportJobProcessor;

  public JobsHandler() {
    this(TcgInventoryFactory.create());
  }

  @VisibleForTesting
  public JobsHandler(TcgInventoryFactory factory) {
    this.objectMapper = factory.objectMapper();
    this.clock = factory.clock();
    this.jobTable = factory.jobTable();
    this.jobsQueue = factory.jobsQueue();
    this.appraiseJobProcessor = new AppraiseJobProcessor(factory);
    this.publishJobProcessor = new PublishJobProcessor(factory);
    this.reportJobProcessor = new ReportJobProcessor(factory);
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

    var jobItem = jobTable.getItem(jobKey);
    if (jobItem == null) {
      throw new IllegalStateException("job item not found: " + message.jobId());
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

    var processor = jobProcessor(message.jobType());
    var result = processor.processBatch(message.user(), jobItem);
    if (result instanceof JobProcessor.FailureJobResult failure) {
      jobItem.setStatus("failed");
      jobItem.setError(failure.error());
      jobItem.setUpdatedAt(clock.now());
      jobTable.putItem(jobItem);
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
    var previousContinuation = jobItem.getContinuation() != null ? jobItem.getContinuation() : 0;

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

  private JobProcessor jobProcessor(String jobType) {
    return switch (jobType) {
      case "appraise" -> appraiseJobProcessor;
      case "publish" -> publishJobProcessor;
      case "report" -> reportJobProcessor;
      default -> throw new IllegalArgumentException("unknown job type: " + jobType);
    };
  }
}
