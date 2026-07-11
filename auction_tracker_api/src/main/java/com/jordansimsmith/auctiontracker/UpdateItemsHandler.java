package com.jordansimsmith.auctiontracker;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.ScheduledEvent;
import com.google.common.annotations.VisibleForTesting;
import com.jordansimsmith.time.Clock;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbIndex;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryEnhancedRequest;

public class UpdateItemsHandler implements RequestHandler<ScheduledEvent, Void> {
  private static final Logger LOGGER = LoggerFactory.getLogger(UpdateItemsHandler.class);

  private final Clock clock;
  private final SearchFactory searchFactory;
  private final TradeMeClient tradeMeClient;
  private final ListingJudge listingJudge;
  private final DynamoDbTable<AuctionTrackerItem> auctionTrackerTable;
  private final DynamoDbIndex<AuctionTrackerItem> gsi1;

  public UpdateItemsHandler() {
    this(AuctionTrackerFactory.create());
  }

  @VisibleForTesting
  UpdateItemsHandler(AuctionTrackerFactory factory) {
    this.clock = factory.clock();
    this.searchFactory = factory.searchFactory();
    this.tradeMeClient = factory.tradeMeClient();
    this.listingJudge = factory.listingJudge();
    this.auctionTrackerTable = factory.auctionTrackerTable();
    this.gsi1 = auctionTrackerTable.index("gsi1");
  }

  @Override
  public Void handleRequest(ScheduledEvent event, Context context) {
    try {
      return doHandleRequest();
    } catch (Exception e) {
      LOGGER.error("Error processing auction updates", e);
      throw new RuntimeException(e);
    }
  }

  private Void doHandleRequest() {
    var searches = searchFactory.findSearches();

    // memoize judgments so a listing found by multiple judged searches is judged once per run
    var judgments = new HashMap<String, Boolean>();
    for (var search : searches) {
      processSearch(search, judgments);
    }

    return null;
  }

  private void processSearch(SearchFactory.Search search, Map<String, Boolean> judgments) {
    var tradeMeItems =
        tradeMeClient.searchItems(
            search.baseUrl(),
            search.searchTerm(),
            search.minPrice(),
            search.maxPrice(),
            search.condition());

    var searchUrl = tradeMeClient.getSearchUrl(search).toString();
    var currentTime = clock.now();

    for (var tradeMeItem : tradeMeItems) {
      if (itemExists(searchUrl, tradeMeItem.url())) {
        continue;
      }

      AuctionTrackerItem.Judgment judgment = null;
      if (search.judge() != null) {
        var pass =
            judgments.computeIfAbsent(
                search.judge().prompt() + AuctionTrackerItem.DELIMITER + tradeMeItem.url(),
                key ->
                    listingJudge.judge(
                        search.judge(), tradeMeItem.title(), tradeMeItem.description()));
        judgment = pass ? AuctionTrackerItem.Judgment.PASS : AuctionTrackerItem.Judgment.FAIL;
      }

      var auctionTrackerItem =
          AuctionTrackerItem.create(
              searchUrl, tradeMeItem.url(), tradeMeItem.title(), currentTime, judgment);
      auctionTrackerTable.putItem(auctionTrackerItem);
    }
  }

  private boolean itemExists(String searchUrl, String itemUrl) {
    return gsi1
        .query(
            QueryEnhancedRequest.builder()
                .queryConditional(
                    QueryConditional.keyEqualTo(
                        Key.builder()
                            .partitionValue(AuctionTrackerItem.formatGsi1pk(searchUrl))
                            .sortValue(AuctionTrackerItem.formatGsi1sk(itemUrl))
                            .build()))
                .build())
        .stream()
        .flatMap(page -> page.items().stream())
        .findFirst()
        .isPresent();
  }
}
