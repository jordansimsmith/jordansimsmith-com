package com.jordansimsmith.auctiontracker;

import static org.assertj.core.api.Assertions.*;

import com.amazonaws.services.lambda.runtime.events.ScheduledEvent;
import com.jordansimsmith.dynamodb.DynamoDbUtils;
import com.jordansimsmith.testcontainers.DynamoDbContainer;
import com.jordansimsmith.time.FakeClock;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;

@Testcontainers
public class UpdateItemsHandlerIntegrationTest {
  private FakeClock fakeClock;
  private FakeSearchFactory fakeSearchFactory;
  private FakeTradeMeClient fakeTradeMeClient;
  private DynamoDbTable<AuctionTrackerItem> auctionTrackerTable;

  private UpdateItemsHandler updateItemsHandler;

  @Container DynamoDbContainer dynamoDbContainer = new DynamoDbContainer();

  @BeforeEach
  void setUp() {
    var factory = AuctionTrackerTestFactory.create(dynamoDbContainer.getEndpoint());

    fakeClock = factory.fakeClock();
    fakeSearchFactory = factory.fakeSearchFactory();
    fakeTradeMeClient = factory.fakeTradeMeClient();
    auctionTrackerTable = factory.auctionTrackerTable();

    DynamoDbUtils.createTable(factory.dynamoDbClient(), auctionTrackerTable);

    updateItemsHandler = new UpdateItemsHandler(factory);
  }

  @Test
  void handleRequestShouldStoreNewItems() {
    // arrange
    fakeClock.setTime(Instant.ofEpochMilli(3_000_000));
    var searchUrl =
        "https://www.trademe.co.nz/a/marketplace/sports/golf/search?search_string=wedge";
    var search = new SearchFactory.Search(URI.create(searchUrl), "wedge", null, null, null);
    fakeSearchFactory.addSearches(List.of(search));

    var tradeMeItems =
        List.of(
            new TradeMeClient.TradeMeItem(
                "https://www.trademe.co.nz/a/marketplace/sports/golf/listing/123",
                "Titleist Wedge",
                "Great condition wedge"),
            new TradeMeClient.TradeMeItem(
                "https://www.trademe.co.nz/a/marketplace/sports/golf/listing/456",
                "Cleveland Wedge",
                "Another wedge"));
    fakeTradeMeClient.addSearchResponse(URI.create(searchUrl), "wedge", tradeMeItems);

    // act
    updateItemsHandler.handleRequest(new ScheduledEvent(), null);

    // assert
    var items = auctionTrackerTable.scan().items().stream().toList();
    assertThat(items).hasSize(2);

    var item1 =
        items.stream()
            .filter(
                item ->
                    item.getUrl()
                        .equals("https://www.trademe.co.nz/a/marketplace/sports/golf/listing/123"))
            .findFirst()
            .orElse(null);
    assertThat(item1).isNotNull();
    assertThat(item1.getTitle()).isEqualTo("Titleist Wedge");
    assertThat(item1.getPk()).isEqualTo("SEARCH#" + searchUrl);
    assertThat(item1.getSk()).startsWith("TIMESTAMP#0000003000");
    assertThat(item1.getTimestamp().getEpochSecond()).isEqualTo(3000);
    assertThat(item1.getTtl()).isEqualTo(3000 + 30 * 24 * 60 * 60);
    assertThat(item1.getGsi1pk()).isEqualTo(AuctionTrackerItem.formatGsi1pk(searchUrl));
    assertThat(item1.getGsi1sk())
        .isEqualTo(
            AuctionTrackerItem.formatGsi1sk(
                "https://www.trademe.co.nz/a/marketplace/sports/golf/listing/123"));

    var item2 =
        items.stream()
            .filter(
                item ->
                    item.getUrl()
                        .equals("https://www.trademe.co.nz/a/marketplace/sports/golf/listing/456"))
            .findFirst()
            .orElse(null);
    assertThat(item2).isNotNull();
    assertThat(item2.getTitle()).isEqualTo("Cleveland Wedge");
  }

  @Test
  void handleRequestShouldNotStoreDuplicateItems() {
    // arrange
    fakeClock.setTime(Instant.ofEpochMilli(3_000_000));
    var searchUrl =
        "https://www.trademe.co.nz/a/marketplace/sports/golf/search?search_string=wedge";
    var search = new SearchFactory.Search(URI.create(searchUrl), "wedge", null, null, null);
    fakeSearchFactory.addSearches(List.of(search));

    var tradeMeItem =
        new TradeMeClient.TradeMeItem(
            "https://www.trademe.co.nz/a/marketplace/sports/golf/listing/123",
            "Titleist Wedge",
            "Great condition wedge");
    fakeTradeMeClient.addSearchResponse(URI.create(searchUrl), "wedge", List.of(tradeMeItem));

    // store item first time
    var existingItem =
        AuctionTrackerItem.create(
            searchUrl,
            "https://www.trademe.co.nz/a/marketplace/sports/golf/listing/123",
            "Titleist Wedge",
            Instant.ofEpochSecond(2000));
    auctionTrackerTable.putItem(existingItem);

    // act
    updateItemsHandler.handleRequest(new ScheduledEvent(), null);

    // assert
    var items = auctionTrackerTable.scan().items().stream().toList();
    assertThat(items).hasSize(1);
    assertThat(items.get(0).getTimestamp().getEpochSecond()).isEqualTo(2000);
  }

  @Test
  void handleRequestShouldProcessMultipleSearches() {
    // arrange
    fakeClock.setTime(Instant.ofEpochMilli(3_000_000));
    var search1 =
        new SearchFactory.Search(
            URI.create("https://www.trademe.co.nz/search1"), "term1", null, null, null);
    var search2 =
        new SearchFactory.Search(
            URI.create("https://www.trademe.co.nz/search2"), "term2", 100.0, 200.0, null);
    fakeSearchFactory.addSearches(List.of(search1, search2));

    fakeTradeMeClient.addSearchResponse(
        URI.create("https://www.trademe.co.nz/search1"),
        "term1",
        List.of(new TradeMeClient.TradeMeItem("url1", "title1", "desc1")));
    fakeTradeMeClient.addSearchResponse(
        URI.create("https://www.trademe.co.nz/search2"),
        "term2",
        List.of(new TradeMeClient.TradeMeItem("url2", "title2", "desc2")));

    // act
    updateItemsHandler.handleRequest(new ScheduledEvent(), null);

    // assert
    var items = auctionTrackerTable.scan().items().stream().toList();
    assertThat(items).hasSize(2);
    assertThat(items.stream().map(AuctionTrackerItem::getUrl))
        .containsExactlyInAnyOrder("url1", "url2");
  }

  @Test
  void handleRequestShouldHandleEmptySearchResults() {
    // arrange
    fakeClock.setTime(Instant.ofEpochMilli(3_000_000));
    var search =
        new SearchFactory.Search(
            URI.create("https://www.trademe.co.nz/search"), "term", null, null, null);
    fakeSearchFactory.addSearches(List.of(search));
    fakeTradeMeClient.addSearchResponse(
        URI.create("https://www.trademe.co.nz/search"), "term", List.of());

    // act
    updateItemsHandler.handleRequest(new ScheduledEvent(), null);

    // assert
    var items = auctionTrackerTable.scan().items().stream().toList();
    assertThat(items).isEmpty();
  }
}
