package com.jordansimsmith.auctiontracker;

import com.jordansimsmith.notifications.NotificationPublisher;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryEnhancedRequest;

public class SendDigestJobProcessor {
  private static final Logger LOGGER = LoggerFactory.getLogger(SendDigestJobProcessor.class);
  private static final String SNS_TOPIC = "auction_tracker_api_digest";
  private static final ZoneId DIGEST_TIME_ZONE = ZoneId.of("Pacific/Auckland");

  private final SearchFactory searchFactory;
  private final TradeMeClient tradeMeClient;
  private final NotificationPublisher notificationPublisher;
  private final DynamoDbTable<AuctionTrackerItem> auctionTrackerTable;

  public SendDigestJobProcessor(
      SearchFactory searchFactory,
      TradeMeClient tradeMeClient,
      NotificationPublisher notificationPublisher,
      DynamoDbTable<AuctionTrackerItem> auctionTrackerTable) {
    this.searchFactory = searchFactory;
    this.tradeMeClient = tradeMeClient;
    this.notificationPublisher = notificationPublisher;
    this.auctionTrackerTable = auctionTrackerTable;
  }

  public void process(Instant windowEnd) {
    var windowStart = windowEnd.atZone(DIGEST_TIME_ZONE).minusDays(1).toInstant();
    var allNewItems =
        searchFactory.findSearches().stream()
            .flatMap(
                search ->
                    findNewItemsForSearch(
                        tradeMeClient.getSearchUrl(search).toString(), windowStart, windowEnd)
                        .stream())
            .filter(item -> item.getJudgment() != AuctionTrackerItem.Judgment.FAIL)
            .collect(
                Collectors.groupingBy(
                    item -> item.getFingerprint() != null ? item.getFingerprint() : item.getUrl()))
            .values()
            .stream()
            .map(items -> items.getFirst())
            .toList();

    if (allNewItems.isEmpty()) {
      LOGGER.info("No new auction items found in digest window ending {}", windowEnd);
      return;
    }

    var digestMessage = buildDigestMessage(allNewItems);
    var subject = String.format("Auction Tracker Daily Digest - %d new items", allNewItems.size());
    notificationPublisher.publish(SNS_TOPIC, subject, digestMessage);
    LOGGER.info("Sent digest with {} new auction items", allNewItems.size());
  }

  private List<AuctionTrackerItem> findNewItemsForSearch(
      String searchUrl, Instant since, Instant until) {
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
        .filter(item -> !item.getTimestamp().isAfter(until))
        .toList();
  }

  private String buildDigestMessage(List<AuctionTrackerItem> items) {
    var messageBuilder = new StringBuilder();
    messageBuilder.append("New auction items found in the last daily window:\n\n");

    for (var item : items) {
      messageBuilder.append(item.getTitle()).append("\n");
      messageBuilder.append(item.getUrl()).append("\n\n");
    }

    return messageBuilder.toString();
  }
}
