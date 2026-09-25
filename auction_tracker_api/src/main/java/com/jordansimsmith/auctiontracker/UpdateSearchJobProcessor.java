package com.jordansimsmith.auctiontracker;

import com.jordansimsmith.time.Clock;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbIndex;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryEnhancedRequest;

public class UpdateSearchJobProcessor {
  private static final Logger LOGGER = LoggerFactory.getLogger(UpdateSearchJobProcessor.class);

  private final Clock clock;
  private final ExcludedSellerUsernameFactory excludedSellerUsernameFactory;
  private final SearchFactory searchFactory;
  private final TradeMeClient tradeMeClient;
  private final ListingFingerprinter listingFingerprinter;
  private final ListingJudge listingJudge;
  private final DynamoDbTable<AuctionTrackerItem> auctionTrackerTable;
  private final DynamoDbIndex<AuctionTrackerItem> gsi1;
  private final DynamoDbIndex<AuctionTrackerItem> gsi2;

  public UpdateSearchJobProcessor(
      Clock clock,
      ExcludedSellerUsernameFactory excludedSellerUsernameFactory,
      SearchFactory searchFactory,
      TradeMeClient tradeMeClient,
      ListingFingerprinter listingFingerprinter,
      ListingJudge listingJudge,
      DynamoDbTable<AuctionTrackerItem> auctionTrackerTable) {
    this.clock = clock;
    this.excludedSellerUsernameFactory = excludedSellerUsernameFactory;
    this.searchFactory = searchFactory;
    this.tradeMeClient = tradeMeClient;
    this.listingFingerprinter = listingFingerprinter;
    this.listingJudge = listingJudge;
    this.auctionTrackerTable = auctionTrackerTable;
    this.gsi1 = auctionTrackerTable.index("gsi1");
    this.gsi2 = auctionTrackerTable.index("gsi2");
  }

  public void process(String searchId) {
    if (searchId == null || searchId.isBlank()) {
      throw new IllegalArgumentException("update_search message is missing search_id");
    }

    var search = searchFactory.getSearch(searchId);
    var excludedSellerUsernames = excludedSellerUsernameFactory.findExcludedSellerUsernames();
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
      if (excludedSellerUsernames.contains(
          tradeMeItem.sellerUsername().trim().toLowerCase(Locale.ROOT))) {
        LOGGER.info(
            "Skipping listing from excluded seller {}: {}",
            tradeMeItem.sellerUsername(),
            tradeMeItem.url());
        continue;
      }

      if (itemExists(searchUrl, tradeMeItem.url())) {
        continue;
      }

      var contentFingerprint = listingFingerprinter.create(tradeMeItem);
      if (contentFingerprintExists(contentFingerprint)) {
        continue;
      }

      AuctionTrackerItem.Judgment judgment = null;
      if (search.judge() != null) {
        var pass =
            listingJudge.judge(search.judge(), tradeMeItem.title(), tradeMeItem.description());
        judgment = pass ? AuctionTrackerItem.Judgment.PASS : AuctionTrackerItem.Judgment.FAIL;
      }

      var auctionTrackerItem =
          AuctionTrackerItem.create(
              searchUrl,
              tradeMeItem.url(),
              tradeMeItem.title(),
              contentFingerprint,
              currentTime,
              judgment);
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

  private boolean contentFingerprintExists(String contentFingerprint) {
    return gsi2
        .query(
            QueryEnhancedRequest.builder()
                .queryConditional(
                    QueryConditional.keyEqualTo(
                        Key.builder()
                            .partitionValue(AuctionTrackerItem.formatGsi2pk(contentFingerprint))
                            .build()))
                .build())
        .stream()
        .flatMap(page -> page.items().stream())
        .findFirst()
        .isPresent();
  }
}
