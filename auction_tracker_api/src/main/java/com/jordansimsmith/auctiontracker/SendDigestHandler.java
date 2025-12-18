package com.jordansimsmith.auctiontracker;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.ScheduledEvent;
import com.google.common.annotations.VisibleForTesting;
import com.jordansimsmith.notifications.NotificationPublisher;
import com.jordansimsmith.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryEnhancedRequest;

public class SendDigestHandler implements RequestHandler<ScheduledEvent, Void> {
  private static final Logger LOGGER = LoggerFactory.getLogger(SendDigestHandler.class);
  private static final String SNS_TOPIC = "auction_tracker_api_digest";

  private final Clock clock;
  private final SearchFactory searchFactory;
  private final TradeMeClient tradeMeClient;
  private final NotificationPublisher notificationPublisher;
  private final DynamoDbTable<AuctionTrackerItem> auctionTrackerTable;

  public SendDigestHandler() {
    this(AuctionTrackerFactory.create());
  }

  @VisibleForTesting
  SendDigestHandler(AuctionTrackerFactory factory) {
    this.clock = factory.clock();
    this.searchFactory = factory.searchFactory();
    this.tradeMeClient = factory.tradeMeClient();
    this.notificationPublisher = factory.notificationPublisher();
    this.auctionTrackerTable = factory.auctionTrackerTable();
  }

  @Override
  public Void handleRequest(ScheduledEvent event, Context context) {
    try {
      return doHandleRequest(event, context);
    } catch (Exception e) {
      LOGGER.error("Error sending auction digest", e);
      throw new RuntimeException(e);
    }
  }

  private Void doHandleRequest(ScheduledEvent event, Context context) {
    var searches = searchFactory.findSearches();
    var currentTime = clock.now();
    var yesterdayTime = currentTime.minus(1, ChronoUnit.DAYS);

    var allNewItems =
        searches.stream()
            .flatMap(
                search ->
                    findNewItemsForSearch(
                        tradeMeClient.getSearchUrl(search).toString(), yesterdayTime)
                        .stream())
            .collect(Collectors.groupingBy(AuctionTrackerItem::getUrl))
            .values()
            .stream()
            .map(items -> items.get(0))
            .toList();

    if (allNewItems.isEmpty()) {
      LOGGER.info("No new auction items found in the last 24 hours");
      return null;
    }

    var digestMessage = buildDigestMessage(allNewItems);
    var subject = String.format("Auction Tracker Daily Digest - %d new items", allNewItems.size());

    notificationPublisher.publish(SNS_TOPIC, subject, digestMessage);
    LOGGER.info("Sent digest with {} new auction items", allNewItems.size());

    return null;
  }

  private List<AuctionTrackerItem> findNewItemsForSearch(String searchUrl, Instant since) {
    var partitionKey = AuctionTrackerItem.formatPk(searchUrl);
    var sortKeyPrefix = AuctionTrackerItem.formatSk(since, null);

    return auctionTrackerTable
        .query(
            QueryEnhancedRequest.builder()
                .queryConditional(
                    QueryConditional.sortGreaterThan(
                        Key.builder()
                            .partitionValue(partitionKey)
                            .sortValue(sortKeyPrefix)
                            .build()))
                .build())
        .items()
        .stream()
        .toList();
  }

  private String buildDigestMessage(List<AuctionTrackerItem> items) {
    var messageBuilder = new StringBuilder();
    messageBuilder.append("New auction items found in the last 24 hours:\n\n");

    for (var item : items) {
      messageBuilder.append(item.getTitle()).append("\n");
      messageBuilder.append(item.getUrl()).append("\n\n");
    }

    return messageBuilder.toString();
  }
}
