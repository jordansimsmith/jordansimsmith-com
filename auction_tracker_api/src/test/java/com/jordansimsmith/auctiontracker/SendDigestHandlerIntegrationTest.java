package com.jordansimsmith.auctiontracker;

import static org.assertj.core.api.Assertions.*;

import com.amazonaws.services.lambda.runtime.events.ScheduledEvent;
import com.jordansimsmith.dynamodb.DynamoDbContainer;
import com.jordansimsmith.dynamodb.DynamoDbUtils;
import com.jordansimsmith.notifications.FakeNotificationPublisher;
import com.jordansimsmith.time.FakeClock;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;

@Testcontainers
public class SendDigestHandlerIntegrationTest {
  private static final BigDecimal START_PRICE = new BigDecimal("100");
  private static final BigDecimal BUY_NOW_PRICE = new BigDecimal("150");

  private FakeClock fakeClock;
  private FakeNotificationPublisher fakeNotificationPublisher;
  private FakeSearchFactory fakeSearchFactory;
  private DynamoDbTable<AuctionTrackerItem> auctionTrackerTable;

  private SendDigestHandler sendDigestHandler;

  @Container private static final DynamoDbContainer dynamoDbContainer = new DynamoDbContainer();

  @BeforeAll
  static void setUpBeforeClass() {
    var factory = AuctionTrackerTestFactory.create(dynamoDbContainer.getEndpoint());
    var table = factory.auctionTrackerTable();
    DynamoDbUtils.createTable(factory.dynamoDbClient(), table);
  }

  @BeforeEach
  void setUp() {
    var factory = AuctionTrackerTestFactory.create(dynamoDbContainer.getEndpoint());

    fakeClock = factory.fakeClock();
    fakeNotificationPublisher = factory.fakeNotificationPublisher();
    fakeSearchFactory = factory.fakeSearchFactory();
    auctionTrackerTable = factory.auctionTrackerTable();

    DynamoDbUtils.reset(factory.dynamoDbClient());

    sendDigestHandler = new SendDigestHandler(factory);
  }

  @Test
  void handleRequestShouldSendDigestWithNewItemsFromLast24Hours() {
    // arrange
    var currentTime = Instant.ofEpochSecond(2_000_000);
    fakeClock.setTime(currentTime);
    var yesterdayTime = currentTime.minus(1, ChronoUnit.DAYS);
    var twoDaysAgo = currentTime.minus(2, ChronoUnit.DAYS);

    var baseUrl = "https://www.trademe.co.nz/search";
    var expectedSearchUrl =
        "https://www.trademe.co.nz/search?search_string=wedge&condition=used&sort_order=expirydesc";
    var search =
        new SearchFactory.Search(
            URI.create(baseUrl), "wedge", null, null, SearchFactory.Condition.USED, null);
    fakeSearchFactory.addSearches(List.of(search));

    // create items - some within 24h, some older
    var recentItem1 =
        AuctionTrackerItem.create(
            expectedSearchUrl,
            "https://www.trademe.co.nz/listing/123",
            "Recent Wedge 1",
            "Recent wedge description 1",
            START_PRICE,
            BUY_NOW_PRICE,
            yesterdayTime.plus(1, ChronoUnit.HOURS), // 23 hours ago
            null);
    var recentItem2 =
        AuctionTrackerItem.create(
            expectedSearchUrl,
            "https://www.trademe.co.nz/listing/456",
            "Recent Wedge 2",
            "Recent wedge description 2",
            START_PRICE,
            BUY_NOW_PRICE,
            yesterdayTime.plus(2, ChronoUnit.HOURS), // 22 hours ago
            null);
    var oldItem =
        AuctionTrackerItem.create(
            expectedSearchUrl,
            "https://www.trademe.co.nz/listing/789",
            "Old Wedge",
            "Old wedge description",
            START_PRICE,
            BUY_NOW_PRICE,
            twoDaysAgo,
            null);

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
        .contains("New auction items found in the last 24 hours:")
        .contains("Recent Wedge 1")
        .contains("https://www.trademe.co.nz/listing/123")
        .contains("Recent Wedge 2")
        .contains("https://www.trademe.co.nz/listing/456");
  }

  @Test
  void handleRequestShouldDeduplicateLegacyListingsByUrlAcrossMultipleSearches() {
    // arrange
    var currentTime = Instant.ofEpochSecond(2_000_000);
    fakeClock.setTime(currentTime);
    var yesterdayTime = currentTime.minus(1, ChronoUnit.DAYS);

    var baseUrl1 = "https://www.trademe.co.nz/category1/search";
    var baseUrl2 = "https://www.trademe.co.nz/category2/search";
    var expectedSearchUrl1 =
        "https://www.trademe.co.nz/category1/search?search_string=item&sort_order=expirydesc";
    var expectedSearchUrl2 =
        "https://www.trademe.co.nz/category2/search?search_string=item&sort_order=expirydesc";

    var search1 =
        new SearchFactory.Search(
            URI.create(baseUrl1), "item", null, null, SearchFactory.Condition.ALL, null);
    var search2 =
        new SearchFactory.Search(
            URI.create(baseUrl2), "item", null, null, SearchFactory.Condition.ALL, null);
    fakeSearchFactory.addSearches(List.of(search1, search2));

    var duplicateListingUrl = "https://www.trademe.co.nz/listing/123";
    var uniqueListingUrl = "https://www.trademe.co.nz/listing/456";

    // same listing found in search 1
    var itemFromSearch1 =
        AuctionTrackerItem.create(
            expectedSearchUrl1,
            duplicateListingUrl,
            "Duplicate Item",
            "Legacy duplicate description",
            START_PRICE,
            BUY_NOW_PRICE,
            yesterdayTime.plus(1, ChronoUnit.HOURS),
            null);

    // same listing found in search 2
    var itemFromSearch2 =
        AuctionTrackerItem.create(
            expectedSearchUrl2,
            duplicateListingUrl,
            "Duplicate Item",
            "Legacy duplicate description",
            START_PRICE,
            BUY_NOW_PRICE,
            yesterdayTime.plus(2, ChronoUnit.HOURS),
            null);

    // unique listing only in search 2
    var uniqueItem =
        AuctionTrackerItem.create(
            expectedSearchUrl2,
            uniqueListingUrl,
            "Unique Item",
            "Unique item description",
            START_PRICE,
            BUY_NOW_PRICE,
            yesterdayTime.plus(3, ChronoUnit.HOURS),
            null);

    itemFromSearch1.setFingerprint(null);
    itemFromSearch1.setGsi2pk(null);
    itemFromSearch1.setGsi2sk(null);
    itemFromSearch2.setFingerprint(null);
    itemFromSearch2.setGsi2pk(null);
    itemFromSearch2.setGsi2sk(null);
    auctionTrackerTable.putItem(itemFromSearch1);
    auctionTrackerTable.putItem(itemFromSearch2);
    auctionTrackerTable.putItem(uniqueItem);

    // act
    sendDigestHandler.handleRequest(new ScheduledEvent(), null);

    // assert
    var notifications = fakeNotificationPublisher.findNotifications("auction_tracker_api_digest");
    assertThat(notifications).hasSize(1);

    var notification = notifications.get(0);
    assertThat(notification.subject()).isEqualTo("Auction Tracker Daily Digest - 2 new items");
    assertThat(notification.message())
        .contains("New auction items found in the last 24 hours:")
        .contains("Duplicate Item")
        .contains("https://www.trademe.co.nz/listing/123")
        .contains("Unique Item")
        .contains("https://www.trademe.co.nz/listing/456");
  }

  @Test
  void handleRequestShouldDeduplicateRelistedItemsByFingerprint() {
    // arrange
    var currentTime = Instant.ofEpochSecond(2_000_000);
    fakeClock.setTime(currentTime);
    var yesterdayTime = currentTime.minus(1, ChronoUnit.DAYS);

    var baseUrl1 = "https://www.trademe.co.nz/category1/search";
    var baseUrl2 = "https://www.trademe.co.nz/category2/search";
    var expectedSearchUrl1 =
        "https://www.trademe.co.nz/category1/search?search_string=item&sort_order=expirydesc";
    var expectedSearchUrl2 =
        "https://www.trademe.co.nz/category2/search?search_string=item&sort_order=expirydesc";
    fakeSearchFactory.addSearches(
        List.of(
            new SearchFactory.Search(
                URI.create(baseUrl1), "item", null, null, SearchFactory.Condition.ALL, null),
            new SearchFactory.Search(
                URI.create(baseUrl2), "item", null, null, SearchFactory.Condition.ALL, null)));

    var itemFromSearch1 =
        AuctionTrackerItem.create(
            expectedSearchUrl1,
            "https://www.trademe.co.nz/listing/123",
            "Relisted Item",
            "Same listing description",
            START_PRICE,
            BUY_NOW_PRICE,
            yesterdayTime.plus(1, ChronoUnit.HOURS),
            null);
    var itemFromSearch2 =
        AuctionTrackerItem.create(
            expectedSearchUrl2,
            "https://www.trademe.co.nz/listing/456",
            "Relisted Item",
            "Same listing description",
            START_PRICE,
            BUY_NOW_PRICE,
            yesterdayTime.plus(2, ChronoUnit.HOURS),
            null);
    auctionTrackerTable.putItem(itemFromSearch1);
    auctionTrackerTable.putItem(itemFromSearch2);

    // act
    sendDigestHandler.handleRequest(new ScheduledEvent(), null);

    // assert
    var notifications = fakeNotificationPublisher.findNotifications("auction_tracker_api_digest");
    assertThat(notifications).hasSize(1);
    assertThat(notifications.get(0).subject())
        .isEqualTo("Auction Tracker Daily Digest - 1 new items");
    var message = notifications.get(0).message();
    assertThat(
            message.contains("https://www.trademe.co.nz/listing/123")
                ^ message.contains("https://www.trademe.co.nz/listing/456"))
        .isTrue();
  }

  @Test
  void handleRequestShouldNotSendDigestWhenNoNewItems() {
    // arrange
    var currentTime = Instant.ofEpochSecond(2_000_000);
    fakeClock.setTime(currentTime);
    var twoDaysAgo = currentTime.minus(2, ChronoUnit.DAYS);

    var baseUrl = "https://www.trademe.co.nz/search";
    var expectedSearchUrl =
        "https://www.trademe.co.nz/search?search_string=wedge&condition=used&sort_order=expirydesc";
    var search =
        new SearchFactory.Search(
            URI.create(baseUrl), "wedge", null, null, SearchFactory.Condition.USED, null);
    fakeSearchFactory.addSearches(List.of(search));

    // create only old items
    var oldItem =
        AuctionTrackerItem.create(
            expectedSearchUrl,
            "https://www.trademe.co.nz/listing/789",
            "Old Wedge",
            "Old wedge description",
            START_PRICE,
            BUY_NOW_PRICE,
            twoDaysAgo,
            null);
    auctionTrackerTable.putItem(oldItem);

    // act
    sendDigestHandler.handleRequest(new ScheduledEvent(), null);

    // assert
    var notifications = fakeNotificationPublisher.findNotifications("auction_tracker_api_digest");
    assertThat(notifications).isEmpty();
  }

  @Test
  void handleRequestShouldExcludeFailJudgedItemsFromDigest() {
    // arrange
    var currentTime = Instant.ofEpochSecond(2_000_000);
    fakeClock.setTime(currentTime);
    var yesterdayTime = currentTime.minus(1, ChronoUnit.DAYS);

    var baseUrl = "https://www.trademe.co.nz/a/marketplace/gaming/trading-cards/magic/search";
    var expectedSearchUrl =
        baseUrl + "?search_string=bulk&price_max=100&condition=used&sort_order=expirydesc";
    var judge =
        new SearchFactory.Judge(
            "prompts/mtg-bulk-judge.md", "gpt-5.4-mini", "none", List.of("mtg_cards"));
    var search =
        new SearchFactory.Search(
            URI.create(baseUrl), "bulk", null, 100.0, SearchFactory.Condition.USED, judge);
    fakeSearchFactory.addSearches(List.of(search));

    var passItem =
        AuctionTrackerItem.create(
            expectedSearchUrl,
            "https://www.trademe.co.nz/listing/123",
            "MTG bulk lot",
            "500 assorted cards",
            START_PRICE,
            BUY_NOW_PRICE,
            yesterdayTime.plus(1, ChronoUnit.HOURS),
            AuctionTrackerItem.Judgment.PASS);
    var failItem =
        AuctionTrackerItem.create(
            expectedSearchUrl,
            "https://www.trademe.co.nz/listing/456",
            "Pokemon bulk lot",
            "500 pokemon cards",
            START_PRICE,
            BUY_NOW_PRICE,
            yesterdayTime.plus(2, ChronoUnit.HOURS),
            AuctionTrackerItem.Judgment.FAIL);

    auctionTrackerTable.putItem(passItem);
    auctionTrackerTable.putItem(failItem);

    // act
    sendDigestHandler.handleRequest(new ScheduledEvent(), null);

    // assert
    var notifications = fakeNotificationPublisher.findNotifications("auction_tracker_api_digest");
    assertThat(notifications).hasSize(1);

    var notification = notifications.get(0);
    assertThat(notification.subject()).isEqualTo("Auction Tracker Daily Digest - 1 new items");
    assertThat(notification.message())
        .contains("MTG bulk lot")
        .contains("https://www.trademe.co.nz/listing/123")
        .doesNotContain("Pokemon bulk lot")
        .doesNotContain("https://www.trademe.co.nz/listing/456");
  }
}
