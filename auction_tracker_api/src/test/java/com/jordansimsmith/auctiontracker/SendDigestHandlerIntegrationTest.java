package com.jordansimsmith.auctiontracker;

import static org.assertj.core.api.Assertions.*;

import com.amazonaws.services.lambda.runtime.events.ScheduledEvent;
import com.jordansimsmith.dynamodb.DynamoDbUtils;
import com.jordansimsmith.notifications.FakeNotificationPublisher;
import com.jordansimsmith.testcontainers.DynamoDbContainer;
import com.jordansimsmith.time.FakeClock;
import java.net.URI;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;

@Testcontainers
public class SendDigestHandlerIntegrationTest {
  private FakeClock fakeClock;
  private FakeNotificationPublisher fakeNotificationPublisher;
  private FakeSearchFactory fakeSearchFactory;
  private DynamoDbTable<AuctionTrackerItem> auctionTrackerTable;

  private SendDigestHandler sendDigestHandler;

  @Container DynamoDbContainer dynamoDbContainer = new DynamoDbContainer();

  @BeforeEach
  void setUp() {
    var factory = AuctionTrackerTestFactory.create(dynamoDbContainer.getEndpoint());

    fakeClock = factory.fakeClock();
    fakeNotificationPublisher = factory.fakeNotificationPublisher();
    fakeSearchFactory = factory.fakeSearchFactory();
    auctionTrackerTable = factory.auctionTrackerTable();

    DynamoDbUtils.createTable(factory.dynamoDbClient(), auctionTrackerTable);

    fakeClock.reset();
    fakeNotificationPublisher.reset();
    fakeSearchFactory.reset();

    sendDigestHandler = new SendDigestHandler(factory);
  }

  @Test
  void handleRequestShouldSendDigestWithNewItemsFromLast24Hours() {
    // arrange
    var currentTime = Instant.ofEpochSecond(2_000_000);
    fakeClock.setTime(currentTime);
    var yesterdayTime = currentTime.minus(1, ChronoUnit.DAYS);
    var twoDaysAgo = currentTime.minus(2, ChronoUnit.DAYS);

    var searchUrl = "https://www.trademe.co.nz/search?q=wedge";
    var search = new SearchFactory.Search(URI.create(searchUrl), "wedge", null, null, null);
    fakeSearchFactory.addSearches(List.of(search));

    // create items - some within 24h, some older
    var recentItem1 =
        AuctionTrackerItem.create(
            searchUrl,
            "https://www.trademe.co.nz/listing/123",
            "Recent Wedge 1",
            yesterdayTime.plus(1, ChronoUnit.HOURS) // 23 hours ago
            );
    var recentItem2 =
        AuctionTrackerItem.create(
            searchUrl,
            "https://www.trademe.co.nz/listing/456",
            "Recent Wedge 2",
            yesterdayTime.plus(2, ChronoUnit.HOURS) // 22 hours ago
            );
    var oldItem =
        AuctionTrackerItem.create(
            searchUrl, "https://www.trademe.co.nz/listing/789", "Old Wedge", twoDaysAgo);

    auctionTrackerTable.putItem(recentItem1);
    auctionTrackerTable.putItem(recentItem2);
    auctionTrackerTable.putItem(oldItem);

    // act
    sendDigestHandler.handleRequest(new ScheduledEvent(), null);

    // assert
    var notifications = fakeNotificationPublisher.findNotifications("auction_tracker_api_digest");
    assertThat(notifications).hasSize(1);

    var notification = notifications.get(0);
    assertThat(notification.subject()).isEqualTo("Auction Tracker Daily Digest - 2 new items");
    assertThat(notification.message())
        .isEqualTo(
            """
        New auction items found in the last 24 hours:

        • Recent Wedge 1
          https://www.trademe.co.nz/listing/123

        • Recent Wedge 2
          https://www.trademe.co.nz/listing/456

        Total: 2 new items""");
  }

  @Test
  void handleRequestShouldNotSendDigestWhenNoNewItems() {
    // arrange
    var currentTime = Instant.ofEpochSecond(2_000_000);
    fakeClock.setTime(currentTime);
    var twoDaysAgo = currentTime.minus(2, ChronoUnit.DAYS);

    var searchUrl = "https://www.trademe.co.nz/search?q=wedge";
    var search = new SearchFactory.Search(URI.create(searchUrl), "wedge", null, null, null);
    fakeSearchFactory.addSearches(List.of(search));

    // create only old items
    var oldItem =
        AuctionTrackerItem.create(
            searchUrl, "https://www.trademe.co.nz/listing/789", "Old Wedge", twoDaysAgo);
    auctionTrackerTable.putItem(oldItem);

    // act
    sendDigestHandler.handleRequest(new ScheduledEvent(), null);

    // assert
    var notifications = fakeNotificationPublisher.findNotifications("auction_tracker_api_digest");
    assertThat(notifications).isEmpty();
  }
}
