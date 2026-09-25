package com.jordansimsmith.auctiontracker;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.annotations.VisibleForTesting;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class JobsHandler implements RequestHandler<SQSEvent, Void> {
  private static final Logger LOGGER = LoggerFactory.getLogger(JobsHandler.class);

  private final ObjectMapper objectMapper;
  private final UpdateSearchJobProcessor updateSearchJobProcessor;
  private final SendDigestJobProcessor sendDigestJobProcessor;

  public JobsHandler() {
    this(AuctionTrackerFactory.create());
  }

  @VisibleForTesting
  JobsHandler(AuctionTrackerFactory factory) {
    this.objectMapper = factory.objectMapper();
    var auctionTrackerTable = factory.auctionTrackerTable();
    this.updateSearchJobProcessor =
        new UpdateSearchJobProcessor(
            factory.clock(),
            factory.excludedSellerUsernameFactory(),
            factory.searchFactory(),
            factory.tradeMeClient(),
            factory.listingFingerprinter(),
            factory.listingJudge(),
            auctionTrackerTable);
    this.sendDigestJobProcessor =
        new SendDigestJobProcessor(
            factory.searchFactory(),
            factory.tradeMeClient(),
            factory.notificationPublisher(),
            auctionTrackerTable);
  }

  @Override
  public Void handleRequest(SQSEvent event, Context context) {
    try {
      doHandleRequest(event);
      return null;
    } catch (Exception e) {
      LOGGER.error("Error processing auction tracker job", e);
      throw new RuntimeException(e);
    }
  }

  private void doHandleRequest(SQSEvent event) throws Exception {
    if (event.getRecords().size() != 1) {
      throw new IllegalArgumentException(
          "expected one SQS record, got " + event.getRecords().size());
    }

    var message = objectMapper.readValue(event.getRecords().getFirst().getBody(), JobMessage.class);
    var scheduledAt = parseScheduledAt(message);
    switch (message.jobType()) {
      case "update_search" -> updateSearchJobProcessor.process(message.searchId());
      case "send_digest" -> sendDigestJobProcessor.process(scheduledAt);
      default -> throw new IllegalArgumentException("unknown job type: " + message.jobType());
    }
  }

  private Instant parseScheduledAt(JobMessage message) {
    if (message.scheduledAt() == null || message.scheduledAt().isBlank()) {
      throw new IllegalArgumentException("job message is missing scheduled_at");
    }
    try {
      return Instant.parse(message.scheduledAt());
    } catch (RuntimeException e) {
      throw new IllegalArgumentException("invalid scheduled_at: " + message.scheduledAt(), e);
    }
  }
}
