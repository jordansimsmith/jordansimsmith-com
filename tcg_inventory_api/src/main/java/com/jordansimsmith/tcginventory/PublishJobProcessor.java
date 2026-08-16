package com.jordansimsmith.tcginventory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PublishJobProcessor {
  private static final Logger LOGGER = LoggerFactory.getLogger(PublishJobProcessor.class);

  private final FetchTcgTokenMinter fetchTcgTokenMinter;
  private final OrderPhaseProcessor orderPhaseProcessor;
  private final ListingPhaseProcessor listingPhaseProcessor;

  public PublishJobProcessor(
      FetchTcgTokenMinter fetchTcgTokenMinter,
      OrderPhaseProcessor orderPhaseProcessor,
      ListingPhaseProcessor listingPhaseProcessor) {
    this.fetchTcgTokenMinter = fetchTcgTokenMinter;
    this.orderPhaseProcessor = orderPhaseProcessor;
    this.listingPhaseProcessor = listingPhaseProcessor;
  }

  public BatchResult processBatch(String user, TcgInventoryItem jobItem) {
    LOGGER.info("starting publish job for user {}", user);
    var bearerToken = fetchTcgTokenMinter.mint(user);
    LOGGER.info("minted FetchTCG bearer token");

    var continuation = jobItem.getContinuation() != null ? jobItem.getContinuation() : 0;

    if (continuation == 0) {
      LOGGER.info("running order phase (continuation=0)");
      orderPhaseProcessor.process(user, bearerToken);
      LOGGER.info("order phase complete");
    } else {
      LOGGER.info("skipping order phase (continuation={})", continuation);
    }

    LOGGER.info("running listing phase");
    var result = listingPhaseProcessor.process(user, bearerToken);
    LOGGER.info(
        "listing phase complete: processedUpTo={}, complete={}",
        result.processedUpTo(),
        result.complete());
    return result;
  }
}
