package com.jordansimsmith.auctiontracker;

import static org.assertj.core.api.Assertions.*;

import com.amazonaws.services.lambda.runtime.events.ScheduledEvent;
import com.jordansimsmith.dynamodb.DynamoDbContainer;
import com.jordansimsmith.dynamodb.DynamoDbUtils;
import com.jordansimsmith.llm.FakeLlmClient;
import com.jordansimsmith.time.FakeClock;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;

@Testcontainers
public class UpdateItemsHandlerIntegrationTest {
  private static final SearchFactory.Judge MTG_JUDGE =
      new SearchFactory.Judge(
          "prompts/mtg-bulk-judge.md",
          "gpt-5.4-mini",
          "none",
          List.of(
              "mtg_cards",
              "bulk_scale",
              "not_basic_lands",
              "not_universes_beyond",
              "civilian_seller",
              "fixed_collection"));

  private FakeClock fakeClock;
  private FakeSearchFactory fakeSearchFactory;
  private FakeTradeMeClient fakeTradeMeClient;
  private FakeLlmClient fakeLlmClient;
  private DynamoDbTable<AuctionTrackerItem> auctionTrackerTable;

  private UpdateItemsHandler updateItemsHandler;

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
    fakeSearchFactory = factory.fakeSearchFactory();
    fakeTradeMeClient = factory.fakeTradeMeClient();
    fakeLlmClient = factory.fakeLlmClient();
    auctionTrackerTable = factory.auctionTrackerTable();

    DynamoDbUtils.reset(factory.dynamoDbClient());

    updateItemsHandler = new UpdateItemsHandler(factory);
  }

  @Test
  void handleRequestShouldStoreNewItems() {
    // arrange
    fakeClock.setTime(Instant.ofEpochMilli(3_000_000));
    var baseUrl = "https://www.trademe.co.nz/a/marketplace/sports/golf/search";
    var expectedSearchUrl =
        "https://www.trademe.co.nz/a/marketplace/sports/golf/search?search_string=wedge&condition=used&sort_order=expirydesc";
    var search =
        new SearchFactory.Search(
            URI.create(baseUrl), "wedge", null, null, SearchFactory.Condition.USED, null);
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
    fakeTradeMeClient.addSearchResponse(
        URI.create(baseUrl), "wedge", null, null, SearchFactory.Condition.USED, tradeMeItems);

    // act
    updateItemsHandler.handleRequest(new ScheduledEvent(), null);

    // assert
    var items = auctionTrackerTable.scan().items().stream().toList();
    assertThat(items).hasSize(2);
    assertThat(items).allSatisfy(item -> assertThat(item.getJudgment()).isNull());
    assertThat(fakeLlmClient.findRequests()).isEmpty();

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
    assertThat(item1.getPk()).isEqualTo("SEARCH#" + expectedSearchUrl);
    assertThat(item1.getSk()).startsWith("TIMESTAMP#0000003000");
    assertThat(item1.getTimestamp().getEpochSecond()).isEqualTo(3000);
    assertThat(item1.getTtl()).isEqualTo(3000 + 30 * 24 * 60 * 60);
    assertThat(item1.getGsi1pk()).isEqualTo(AuctionTrackerItem.formatGsi1pk(expectedSearchUrl));
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
    var baseUrl = "https://www.trademe.co.nz/a/marketplace/sports/golf/search";
    var expectedSearchUrl =
        "https://www.trademe.co.nz/a/marketplace/sports/golf/search?search_string=wedge&condition=used&sort_order=expirydesc";
    var search =
        new SearchFactory.Search(
            URI.create(baseUrl), "wedge", null, null, SearchFactory.Condition.USED, null);
    fakeSearchFactory.addSearches(List.of(search));

    var tradeMeItem =
        new TradeMeClient.TradeMeItem(
            "https://www.trademe.co.nz/a/marketplace/sports/golf/listing/123",
            "Titleist Wedge",
            "Great condition wedge");
    fakeTradeMeClient.addSearchResponse(
        URI.create(baseUrl),
        "wedge",
        null,
        null,
        SearchFactory.Condition.USED,
        List.of(tradeMeItem));

    // store item first time
    var existingItem =
        AuctionTrackerItem.create(
            expectedSearchUrl,
            "https://www.trademe.co.nz/a/marketplace/sports/golf/listing/123",
            "Titleist Wedge",
            Instant.ofEpochSecond(2000),
            null);
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
            URI.create("https://www.trademe.co.nz/search1"),
            "term1",
            null,
            null,
            SearchFactory.Condition.USED,
            null);
    var search2 =
        new SearchFactory.Search(
            URI.create("https://www.trademe.co.nz/search2"),
            "term2",
            100.0,
            200.0,
            SearchFactory.Condition.USED,
            null);
    fakeSearchFactory.addSearches(List.of(search1, search2));

    fakeTradeMeClient.addSearchResponse(
        URI.create("https://www.trademe.co.nz/search1"),
        "term1",
        null,
        null,
        SearchFactory.Condition.USED,
        List.of(new TradeMeClient.TradeMeItem("url1", "title1", "desc1")));
    fakeTradeMeClient.addSearchResponse(
        URI.create("https://www.trademe.co.nz/search2"),
        "term2",
        100.0,
        200.0,
        SearchFactory.Condition.USED,
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
            URI.create("https://www.trademe.co.nz/search"),
            "term",
            null,
            null,
            SearchFactory.Condition.USED,
            null);
    fakeSearchFactory.addSearches(List.of(search));
    fakeTradeMeClient.addSearchResponse(
        URI.create("https://www.trademe.co.nz/search"),
        "term",
        null,
        null,
        SearchFactory.Condition.USED,
        List.of());

    // act
    updateItemsHandler.handleRequest(new ScheduledEvent(), null);

    // assert
    var items = auctionTrackerTable.scan().items().stream().toList();
    assertThat(items).isEmpty();
  }

  @Test
  void handleRequestShouldStoreJudgmentForJudgedSearch() {
    // arrange
    fakeClock.setTime(Instant.ofEpochMilli(3_000_000));
    var baseUrl = "https://www.trademe.co.nz/a/marketplace/gaming/trading-cards/magic/search";
    var search =
        new SearchFactory.Search(
            URI.create(baseUrl), "bulk", null, 100.0, SearchFactory.Condition.USED, MTG_JUDGE);
    fakeSearchFactory.addSearches(List.of(search));

    fakeTradeMeClient.addSearchResponse(
        URI.create(baseUrl),
        "bulk",
        null,
        100.0,
        SearchFactory.Condition.USED,
        List.of(
            new TradeMeClient.TradeMeItem("url1", "MTG bulk lot", "500 assorted cards"),
            new TradeMeClient.TradeMeItem("url2", "Pokemon bulk", "500 pokemon cards")));
    fakeLlmClient.addResponse(judgmentJson(true));
    fakeLlmClient.addResponse(judgmentJson(false));

    // act
    updateItemsHandler.handleRequest(new ScheduledEvent(), null);

    // assert
    var items = auctionTrackerTable.scan().items().stream().toList();
    assertThat(items).hasSize(2);
    var item1 = items.stream().filter(i -> i.getUrl().equals("url1")).findFirst().orElseThrow();
    assertThat(item1.getJudgment()).isEqualTo(AuctionTrackerItem.Judgment.PASS);
    var item2 = items.stream().filter(i -> i.getUrl().equals("url2")).findFirst().orElseThrow();
    assertThat(item2.getJudgment()).isEqualTo(AuctionTrackerItem.Judgment.FAIL);
    assertThat(fakeLlmClient.findRequests()).hasSize(2);
  }

  @Test
  void handleRequestShouldNotJudgeExistingItems() {
    // arrange
    fakeClock.setTime(Instant.ofEpochMilli(3_000_000));
    var baseUrl = "https://www.trademe.co.nz/a/marketplace/gaming/trading-cards/magic/search";
    var expectedSearchUrl =
        baseUrl + "?search_string=bulk&price_max=100&condition=used&sort_order=expirydesc";
    var search =
        new SearchFactory.Search(
            URI.create(baseUrl), "bulk", null, 100.0, SearchFactory.Condition.USED, MTG_JUDGE);
    fakeSearchFactory.addSearches(List.of(search));

    fakeTradeMeClient.addSearchResponse(
        URI.create(baseUrl),
        "bulk",
        null,
        100.0,
        SearchFactory.Condition.USED,
        List.of(new TradeMeClient.TradeMeItem("url1", "MTG bulk lot", "500 assorted cards")));

    var existingItem =
        AuctionTrackerItem.create(
            expectedSearchUrl,
            "url1",
            "MTG bulk lot",
            Instant.ofEpochSecond(2000),
            AuctionTrackerItem.Judgment.FAIL);
    auctionTrackerTable.putItem(existingItem);

    // act
    updateItemsHandler.handleRequest(new ScheduledEvent(), null);

    // assert
    var items = auctionTrackerTable.scan().items().stream().toList();
    assertThat(items).hasSize(1);
    assertThat(items.get(0).getJudgment()).isEqualTo(AuctionTrackerItem.Judgment.FAIL);
    assertThat(fakeLlmClient.findRequests()).isEmpty();
  }

  @Test
  void handleRequestShouldJudgeOnceWhenItemInMultipleJudgedSearches() {
    // arrange
    fakeClock.setTime(Instant.ofEpochMilli(3_000_000));
    var baseUrl = "https://www.trademe.co.nz/a/marketplace/gaming/trading-cards/magic/search";
    var search1 =
        new SearchFactory.Search(
            URI.create(baseUrl), "bulk", null, 100.0, SearchFactory.Condition.USED, MTG_JUDGE);
    var search2 =
        new SearchFactory.Search(
            URI.create(baseUrl),
            "collection",
            null,
            100.0,
            SearchFactory.Condition.USED,
            MTG_JUDGE);
    fakeSearchFactory.addSearches(List.of(search1, search2));

    var tradeMeItem =
        new TradeMeClient.TradeMeItem("url1", "MTG bulk collection", "500 assorted cards");
    fakeTradeMeClient.addSearchResponse(
        URI.create(baseUrl),
        "bulk",
        null,
        100.0,
        SearchFactory.Condition.USED,
        List.of(tradeMeItem));
    fakeTradeMeClient.addSearchResponse(
        URI.create(baseUrl),
        "collection",
        null,
        100.0,
        SearchFactory.Condition.USED,
        List.of(tradeMeItem));
    fakeLlmClient.addResponse(judgmentJson(false));

    // act
    updateItemsHandler.handleRequest(new ScheduledEvent(), null);

    // assert
    var items = auctionTrackerTable.scan().items().stream().toList();
    assertThat(items).hasSize(2);
    assertThat(items)
        .allSatisfy(
            item -> assertThat(item.getJudgment()).isEqualTo(AuctionTrackerItem.Judgment.FAIL));
    assertThat(fakeLlmClient.findRequests()).hasSize(1);
  }

  @Test
  void handleRequestShouldUseConfiguredModelPerJudge() {
    // arrange
    fakeClock.setTime(Instant.ofEpochMilli(3_000_000));
    var otherJudge =
        new SearchFactory.Judge(
            "prompts/mtg-bulk-judge.md", "gpt-5.4-nano", "low", List.of("mtg_cards"));
    var search1 =
        new SearchFactory.Search(
            URI.create("https://www.trademe.co.nz/search1"),
            "term1",
            null,
            null,
            SearchFactory.Condition.USED,
            MTG_JUDGE);
    var search2 =
        new SearchFactory.Search(
            URI.create("https://www.trademe.co.nz/search2"),
            "term2",
            null,
            null,
            SearchFactory.Condition.USED,
            otherJudge);
    fakeSearchFactory.addSearches(List.of(search1, search2));

    fakeTradeMeClient.addSearchResponse(
        URI.create("https://www.trademe.co.nz/search1"),
        "term1",
        null,
        null,
        SearchFactory.Condition.USED,
        List.of(new TradeMeClient.TradeMeItem("url1", "title1", "desc1")));
    fakeTradeMeClient.addSearchResponse(
        URI.create("https://www.trademe.co.nz/search2"),
        "term2",
        null,
        null,
        SearchFactory.Condition.USED,
        List.of(new TradeMeClient.TradeMeItem("url2", "title2", "desc2")));
    fakeLlmClient.addResponse(judgmentJson(true));
    fakeLlmClient.addResponse(
        "{\"mtg_cards\": {\"reasoning\": \"because\", \"result\": \"pass\"}}");

    // act
    updateItemsHandler.handleRequest(new ScheduledEvent(), null);

    // assert
    var requests = fakeLlmClient.findRequests();
    assertThat(requests).hasSize(2);
    assertThat(requests.get(0).model()).isEqualTo("gpt-5.4-mini");
    assertThat(requests.get(0).reasoningEffort()).isEqualTo("none");
    assertThat(requests.get(1).model()).isEqualTo("gpt-5.4-nano");
    assertThat(requests.get(1).reasoningEffort()).isEqualTo("low");

    var items = auctionTrackerTable.scan().items().stream().toList();
    assertThat(items).hasSize(2);
    assertThat(items)
        .allSatisfy(
            item -> assertThat(item.getJudgment()).isEqualTo(AuctionTrackerItem.Judgment.PASS));
  }

  @Test
  void handleRequestShouldThrowWhenJudgeFails() {
    // arrange
    fakeClock.setTime(Instant.ofEpochMilli(3_000_000));
    var baseUrl = "https://www.trademe.co.nz/a/marketplace/gaming/trading-cards/magic/search";
    var search =
        new SearchFactory.Search(
            URI.create(baseUrl), "bulk", null, 100.0, SearchFactory.Condition.USED, MTG_JUDGE);
    fakeSearchFactory.addSearches(List.of(search));

    fakeTradeMeClient.addSearchResponse(
        URI.create(baseUrl),
        "bulk",
        null,
        100.0,
        SearchFactory.Condition.USED,
        List.of(new TradeMeClient.TradeMeItem("url1", "MTG bulk lot", "500 assorted cards")));
    // no llm response queued, so the judge call fails

    // act & assert
    assertThatThrownBy(() -> updateItemsHandler.handleRequest(new ScheduledEvent(), null))
        .isInstanceOf(RuntimeException.class);
    assertThat(auctionTrackerTable.scan().items().stream().toList()).isEmpty();
  }

  private static String judgmentJson(boolean pass) {
    var result = pass ? "pass" : "fail";
    var criteria =
        List.of(
            "mtg_cards",
            "bulk_scale",
            "not_basic_lands",
            "not_universes_beyond",
            "civilian_seller",
            "fixed_collection");
    var builder = new StringBuilder("{");
    for (var i = 0; i < criteria.size(); i++) {
      builder.append(
          "\"%s\": {\"reasoning\": \"because\", \"result\": \"%s\"}"
              .formatted(criteria.get(i), result));
      if (i < criteria.size() - 1) {
        builder.append(",");
      }
    }
    return builder.append("}").toString();
  }
}
