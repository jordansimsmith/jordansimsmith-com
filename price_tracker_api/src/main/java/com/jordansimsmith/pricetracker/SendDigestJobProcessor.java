package com.jordansimsmith.pricetracker;

import com.jordansimsmith.notifications.NotificationPublisher;
import com.jordansimsmith.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.StringJoiner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.model.GetItemEnhancedRequest;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryEnhancedRequest;

public class SendDigestJobProcessor {
  private static final Logger LOGGER = LoggerFactory.getLogger(SendDigestJobProcessor.class);
  public static final String TOPIC = "price_tracker_api_price_updates";

  private record PriceChange(String url, String name, double currentPrice, double previousPrice) {}

  private final Clock clock;
  private final NotificationPublisher notificationPublisher;
  private final ProductsFactory productsFactory;
  private final DynamoDbTable<PriceTrackerItem> priceTrackerTable;
  private final DynamoDbTable<DigestCheckpointItem> digestCheckpointTable;

  public SendDigestJobProcessor(
      Clock clock,
      NotificationPublisher notificationPublisher,
      ProductsFactory productsFactory,
      DynamoDbTable<PriceTrackerItem> priceTrackerTable,
      DynamoDbTable<DigestCheckpointItem> digestCheckpointTable) {
    this.clock = clock;
    this.notificationPublisher = notificationPublisher;
    this.productsFactory = productsFactory;
    this.priceTrackerTable = priceTrackerTable;
    this.digestCheckpointTable = digestCheckpointTable;
  }

  public void process(Instant scheduledAt) {
    var cutoff = clock.now().truncatedTo(ChronoUnit.SECONDS).minusSeconds(1);
    var checkpointKey =
        Key.builder()
            .partitionValue(DigestCheckpointItem.PK_VALUE)
            .sortValue(DigestCheckpointItem.SK_VALUE)
            .build();
    var checkpoint =
        digestCheckpointTable.getItem(
            GetItemEnhancedRequest.builder().key(checkpointKey).consistentRead(true).build());
    var previousCutoff =
        checkpoint == null
            ? scheduledAt.minus(1, ChronoUnit.HOURS)
            : checkpoint.getProcessedThrough();

    var priceChanges = new ArrayList<PriceChange>();
    for (var product : productsFactory.findProducts()) {
      var previousPrice = findLatest(product, previousCutoff);
      var currentPrice = findLatest(product, cutoff);
      if (previousPrice == null || currentPrice == null) {
        continue;
      }
      if (currentPrice.getPrice() < previousPrice.getPrice()) {
        priceChanges.add(
            new PriceChange(
                currentPrice.getUrl(),
                currentPrice.getName(),
                currentPrice.getPrice(),
                previousPrice.getPrice()));
      }
    }

    if (!priceChanges.isEmpty()) {
      var subject =
          priceChanges.size() == 1
              ? "1 price decreased"
              : "%d prices decreased".formatted(priceChanges.size());
      var message = new StringJoiner("\r\n\r\n");
      for (var priceChange : priceChanges) {
        message.add(
            "%s $%.2f -> $%.2f %s"
                .formatted(
                    priceChange.name,
                    priceChange.previousPrice,
                    priceChange.currentPrice,
                    priceChange.url));
      }
      notificationPublisher.publish(TOPIC, subject, message.toString());
    } else {
      LOGGER.info("No prices decreased since the previous digest");
    }

    digestCheckpointTable.putItem(DigestCheckpointItem.create(cutoff));
  }

  private PriceTrackerItem findLatest(ProductsFactory.Product product, Instant cutoff) {
    var key =
        Key.builder()
            .partitionValue(PriceTrackerItem.formatPk(product.url().toString()))
            .sortValue(PriceTrackerItem.formatSk(cutoff))
            .build();
    return priceTrackerTable
        .query(
            QueryEnhancedRequest.builder()
                .queryConditional(QueryConditional.sortLessThanOrEqualTo(key))
                .limit(1)
                .scanIndexForward(false)
                .consistentRead(true)
                .build())
        .items()
        .stream()
        .findFirst()
        .orElse(null);
  }
}
