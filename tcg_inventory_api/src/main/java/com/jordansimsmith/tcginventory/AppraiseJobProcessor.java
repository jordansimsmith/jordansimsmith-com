package com.jordansimsmith.tcginventory;

import com.jordansimsmith.time.Clock;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;

class AppraiseJobProcessor {
  static final int BATCH_SIZE = 100;

  private final DynamoDbTable<TcgInventoryItem> tcgInventoryTable;
  private final Clock clock;
  private final FetchTcgClient fetchTcgClient;
  private final PricingPolicy pricingPolicy;

  AppraiseJobProcessor(
      DynamoDbTable<TcgInventoryItem> tcgInventoryTable,
      Clock clock,
      FetchTcgClient fetchTcgClient) {
    this.tcgInventoryTable = tcgInventoryTable;
    this.clock = clock;
    this.fetchTcgClient = fetchTcgClient;
    this.pricingPolicy = new PricingPolicy();
  }

  BatchResult processBatch(String user, TcgInventoryItem jobItem) {
    var importId = jobItem.getImportId();
    var continuation = jobItem.getContinuation() != null ? jobItem.getContinuation() : 0;

    var importKey =
        Key.builder()
            .partitionValue(TcgInventoryItem.formatUserPk(user))
            .sortValue(TcgInventoryItem.formatImportSk(importId))
            .build();
    var importItem = tcgInventoryTable.getItem(importKey);
    var totalRows = importItem.getRowCount() != null ? importItem.getRowCount() : 0;

    int batchEnd = Math.min(continuation + BATCH_SIZE, totalRows);
    int processed = continuation;

    int keepCount = 0;
    int discardCount = 0;
    int reviewCount = 0;

    Map<String, ResolvedCard> batchCache = new HashMap<>();

    for (int i = continuation + 1; i <= batchEnd; i++) {
      var rowKey =
          Key.builder()
              .partitionValue(TcgInventoryItem.formatImportRowPk(user, importId))
              .sortValue(TcgInventoryItem.formatImportRowSk(i))
              .build();
      var rowItem = tcgInventoryTable.getItem(rowKey);
      if (rowItem == null || rowItem.getDecision() != null) {
        processed = i;
        continue;
      }

      var decision = appraiseRow(rowItem, batchCache);
      rowItem.setDecision(decision.decision());
      rowItem.setDecisionReason(decision.reason());
      rowItem.setMarketPrice(decision.marketPrice());
      rowItem.setSuggestedPrice(decision.suggestedPrice());
      tcgInventoryTable.putItem(rowItem);

      switch (decision.decision()) {
        case "keep" -> keepCount++;
        case "discard" -> discardCount++;
        case "review" -> reviewCount++;
      }

      processed = i;
    }

    boolean complete = processed >= totalRows;
    importItem.setKeepCount(
        (importItem.getKeepCount() != null ? importItem.getKeepCount() : 0) + keepCount);
    importItem.setDiscardCount(
        (importItem.getDiscardCount() != null ? importItem.getDiscardCount() : 0) + discardCount);
    importItem.setReviewCount(
        (importItem.getReviewCount() != null ? importItem.getReviewCount() : 0) + reviewCount);
    if (complete) {
      importItem.setStatus("review");
    }
    importItem.setUpdatedAt(clock.now());
    tcgInventoryTable.putItem(importItem);

    return new BatchResult(processed, complete);
  }

  private RowDecision appraiseRow(TcgInventoryItem rowItem, Map<String, ResolvedCard> batchCache) {
    if (!"en".equals(rowItem.getLanguage())) {
      return RowDecision.review("non-english");
    }

    var setCode = rowItem.getSetCode();
    if (!FetchTcgSetMapping.contains(setCode)) {
      return RowDecision.review("unmapped set");
    }

    var dedupeKey = rowItem.getScryfallId() + "#" + rowItem.getFinish();
    var cached = batchCache.get(dedupeKey);
    if (cached == null) {
      cached = resolveCard(setCode, rowItem.getName(), rowItem.getFinish());
      if (cached == null) {
        return RowDecision.review("unresolvable");
      }
      batchCache.put(dedupeKey, cached);
    }

    var rivals = buildRivalTiers(cached.cardId(), rowItem.getCondition());
    var result = pricingPolicy.appraise(cached.marketPrice(), rivals);

    if (result.decision() == PricingPolicy.Decision.DISCARD) {
      return RowDecision.discard("below threshold", cached.marketPrice().toPlainString());
    }

    return RowDecision.keep(
        cached.marketPrice().toPlainString(), result.suggestedPrice().toPlainString());
  }

  private ResolvedCard resolveCard(String setCode, String cardName, String finish) {
    var searchName = cardName.contains("//") ? cardName.split("//")[0].trim() : cardName;
    var setEntries = FetchTcgSetMapping.get(setCode);
    for (var entry : setEntries) {
      var searchResult = fetchTcgClient.searchCards(entry.setId(), searchName, finish);
      if (!searchResult.content().isEmpty()) {
        var card = searchResult.content().get(0);
        var cardDetails = fetchTcgClient.getCard(card.id());
        var pricingData = cardDetails.pricingData();
        var nzPricing = pricingData != null ? pricingData.get("NZ") : null;
        var marketPrice =
            nzPricing != null && nzPricing.tcgMarketPrice() != null
                ? nzPricing.tcgMarketPrice()
                : BigDecimal.ZERO;
        return new ResolvedCard(card.id(), marketPrice);
      }
    }
    return null;
  }

  private List<PricingPolicy.RivalTier> buildRivalTiers(String cardId, String condition) {
    var listingsResponse = fetchTcgClient.getCardListings(cardId);
    var skuCondition = Condition.valueOf(condition);

    TreeMap<BigDecimal, Set<String>> priceToSellers = new TreeMap<>();
    for (var listing : listingsResponse.content()) {
      var listingCondition = Condition.fromFetchtcg(listing.condition());
      if (listingCondition == null || !listingCondition.isSameOrBetterThan(skuCondition)) {
        continue;
      }
      priceToSellers
          .computeIfAbsent(listing.listedPrice(), k -> new HashSet<>())
          .add(listing.sellerProfileName());
    }

    var tiers = new ArrayList<PricingPolicy.RivalTier>();
    for (var entry : priceToSellers.entrySet()) {
      tiers.add(new PricingPolicy.RivalTier(entry.getKey(), entry.getValue()));
    }
    return tiers;
  }

  private record ResolvedCard(String cardId, BigDecimal marketPrice) {}

  private record RowDecision(
      String decision, String reason, String marketPrice, String suggestedPrice) {
    static RowDecision keep(String marketPrice, String suggestedPrice) {
      return new RowDecision("keep", null, marketPrice, suggestedPrice);
    }

    static RowDecision discard(String reason, String marketPrice) {
      return new RowDecision("discard", reason, marketPrice, null);
    }

    static RowDecision review(String reason) {
      return new RowDecision("review", reason, null, null);
    }
  }
}
