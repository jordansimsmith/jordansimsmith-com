package com.jordansimsmith.auctiontracker;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.ScheduledEvent;
import com.google.common.annotations.VisibleForTesting;
import com.jordansimsmith.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;

public class UpdateItemsHandler implements RequestHandler<ScheduledEvent, Void> {
  private static final Logger logger = LoggerFactory.getLogger(UpdateItemsHandler.class);

  private final Clock clock;
  private final SearchFactory searchFactory;
  private final TradeMeClient tradeMeClient;
  private final DynamoDbTable<AuctionTrackerItem> auctionTrackerTable;

  public UpdateItemsHandler() {
    this(AuctionTrackerFactory.create());
  }

  @VisibleForTesting
  UpdateItemsHandler(AuctionTrackerFactory factory) {
    this.clock = factory.clock();
    this.searchFactory = factory.searchFactory();
    this.tradeMeClient = factory.tradeMeClient();
    this.auctionTrackerTable = factory.auctionTrackerTable();
  }

  @Override
  public Void handleRequest(ScheduledEvent event, Context context) {
    try {
      return doHandleRequest(event, context);
    } catch (Exception e) {
      logger.error("Error processing auction updates", e);
      throw new RuntimeException(e);
    }
  }

  private Void doHandleRequest(ScheduledEvent event, Context context) throws Exception {
    logger.info("Starting update items process");
    // TODO: Implement item update logic
    logger.info("Completed update items process");
    return null;
  }
}
