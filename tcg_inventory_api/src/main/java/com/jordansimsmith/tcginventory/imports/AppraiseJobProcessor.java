package com.jordansimsmith.tcginventory.imports;

import com.jordansimsmith.tcginventory.BatchResult;
import com.jordansimsmith.tcginventory.CardIdentity;
import com.jordansimsmith.tcginventory.Condition;
import com.jordansimsmith.tcginventory.JobItem;
import com.jordansimsmith.tcginventory.fetchtcg.FetchTcgClient;
import com.jordansimsmith.tcginventory.imports.AppraisalCatalogs.AppraisalCatalog;
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

public class AppraiseJobProcessor {
  public static final int BATCH_SIZE = 100;

  private final DynamoDbTable<ImportItem> importTable;
  private final DynamoDbTable<ImportRowItem> importRowTable;
  private final Clock clock;
  private final FetchTcgClient fetchTcgClient;
  private final PricingPolicy pricingPolicy;

  public AppraiseJobProcessor(
      DynamoDbTable<ImportItem> importTable,
      DynamoDbTable<ImportRowItem> importRowTable,
      Clock clock,
      FetchTcgClient fetchTcgClient) {
    this.importTable = importTable;
    this.importRowTable = importRowTable;
    this.clock = clock;
    this.fetchTcgClient = fetchTcgClient;
    this.pricingPolicy = new PricingPolicy();
  }

  public BatchResult processBatch(String user, JobItem jobItem) {
    var importId = jobItem.getImportId();
    var continuation = jobItem.getContinuation() != null ? jobItem.getContinuation() : 0;

    var importKey =
        Key.builder()
            .partitionValue(ImportItem.formatPk(user))
            .sortValue(ImportItem.formatSk(importId))
            .build();
    var importItem = importTable.getItem(importKey);
    var totalRows = importItem.getRowCount() != null ? importItem.getRowCount() : 0;

    int batchEnd = Math.min(continuation + BATCH_SIZE, totalRows);
    int processed = continuation;

    var appraisalCatalog = AppraisalCatalogs.get(importItem.getGame());
    Map<String, AppraisalCatalog.ResolvedCard> batchCache = new HashMap<>();
    Map<String, FetchTcgClient.GetCardResponse> cardCache = new HashMap<>();

    for (int i = continuation + 1; i <= batchEnd; i++) {
      var rowKey =
          Key.builder()
              .partitionValue(ImportRowItem.formatPk(user, importId))
              .sortValue(ImportRowItem.formatSk(i))
              .build();
      var rowItem = importRowTable.getItem(rowKey);
      if (rowItem == null || rowItem.getDecision() != null) {
        processed = i;
        continue;
      }

      var decision =
          appraiseRow(importItem.getGame(), appraisalCatalog, rowItem, batchCache, cardCache);
      rowItem.setDecision(decision.decision());
      rowItem.setDecisionReason(decision.reason());
      rowItem.setMarketPrice(decision.marketPrice());
      rowItem.setSuggestedPrice(decision.suggestedPrice());
      rowItem.setFetchtcgCardId(decision.fetchtcgCardId());
      rowItem.setFetchtcgSetId(decision.fetchtcgSetId());
      importRowTable.putItem(rowItem);

      processed = i;
    }

    boolean complete = processed >= totalRows;
    if (complete) {
      importItem.setStatus("review");
    }
    importItem.setUpdatedAt(clock.now());
    importTable.putItem(importItem);

    return new BatchResult(processed, complete);
  }

  private RowDecision appraiseRow(
      String game,
      AppraisalCatalog appraisalCatalog,
      ImportRowItem rowItem,
      Map<String, AppraisalCatalog.ResolvedCard> batchCache,
      Map<String, FetchTcgClient.GetCardResponse> cardCache) {
    var identity = new CardIdentity(game, rowItem.getExternalSource(), rowItem.getExternalId());
    var dedupeKey =
        identity.game()
            + "#"
            + identity.externalSource()
            + "#"
            + identity.externalId()
            + "#"
            + rowItem.getFinish()
            + "#"
            + rowItem.getSetCode()
            + "#"
            + rowItem.getLanguage();
    var cached = batchCache.get(dedupeKey);
    if (cached == null) {
      var resolution = appraisalCatalog.resolve(identity, rowItem, fetchTcgClient, cardCache);
      if (resolution.reviewReason() != null) {
        return RowDecision.review(resolution.reviewReason());
      }
      cached = resolution.card();
      batchCache.put(dedupeKey, cached);
    }

    var rivals = buildRivalTiers(cached.cardId(), rowItem.getCondition());
    var result = pricingPolicy.appraise(cached.marketPrice(), rivals);

    if (result.decision() == PricingPolicy.Decision.DISCARD) {
      return RowDecision.discard("below threshold", cached.marketPrice().toPlainString());
    }

    return RowDecision.keep(
        cached.marketPrice().toPlainString(),
        result.suggestedPrice().toPlainString(),
        cached.cardId(),
        cached.setId());
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

  private record RowDecision(
      String decision,
      String reason,
      String marketPrice,
      String suggestedPrice,
      String fetchtcgCardId,
      Integer fetchtcgSetId) {
    static RowDecision keep(
        String marketPrice, String suggestedPrice, String fetchtcgCardId, int fetchtcgSetId) {
      return new RowDecision(
          "keep", null, marketPrice, suggestedPrice, fetchtcgCardId, fetchtcgSetId);
    }

    static RowDecision discard(String reason, String marketPrice) {
      return new RowDecision("discard", reason, marketPrice, null, null, null);
    }

    static RowDecision review(String reason) {
      return new RowDecision("review", reason, null, null, null, null);
    }
  }
}
