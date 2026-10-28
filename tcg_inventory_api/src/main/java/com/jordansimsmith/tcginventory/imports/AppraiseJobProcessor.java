package com.jordansimsmith.tcginventory.imports;

import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.service.ServiceException;
import com.jordansimsmith.service.ServiceUnavailableException;
import com.jordansimsmith.tcginventory.CardIdentity;
import com.jordansimsmith.tcginventory.Condition;
import com.jordansimsmith.tcginventory.JobItem;
import com.jordansimsmith.tcginventory.JobProcessor;
import com.jordansimsmith.tcginventory.fetchtcg.client.FetchTcgService;
import com.jordansimsmith.tcginventory.fetchtcg.client.GetAccountRequest;
import com.jordansimsmith.tcginventory.fetchtcg.client.GetCardRequest;
import com.jordansimsmith.tcginventory.fetchtcg.client.GetCardResponse;
import com.jordansimsmith.tcginventory.imports.AppraisalCatalogs.AppraisalCatalog;
import com.jordansimsmith.tcginventory.pricing.CompetitorPricing;
import com.jordansimsmith.tcginventory.pricing.Pricing;
import com.jordansimsmith.tcginventory.pricing.PricingPolicy;
import com.jordansimsmith.tcginventory.pricing.PricingRepository;
import com.jordansimsmith.time.Clock;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;

public class AppraiseJobProcessor implements JobProcessor {
  public static final int BATCH_SIZE = 100;

  private final DynamoDbTable<ImportItem> importTable;
  private final DynamoDbTable<ImportRowItem> importRowTable;
  private final Clock clock;
  private final FetchTcgService fetchTcgService;
  private final CompetitorPricing competitorPricing;
  private final AppraisalPolicy appraisalPolicy;
  private final PricingPolicy pricingPolicy;
  private final PricingRepository pricingRepository;

  public AppraiseJobProcessor(
      DynamoDbTable<ImportItem> importTable,
      DynamoDbTable<ImportRowItem> importRowTable,
      Clock clock,
      FetchTcgService fetchTcgService,
      PricingRepository pricingRepository) {
    this.importTable = importTable;
    this.importRowTable = importRowTable;
    this.clock = clock;
    this.fetchTcgService = fetchTcgService;
    this.competitorPricing = new CompetitorPricing(fetchTcgService);
    this.appraisalPolicy = new AppraisalPolicy();
    this.pricingPolicy = new PricingPolicy();
    this.pricingRepository = pricingRepository;
  }

  @Override
  public JobProcessor.JobResult processBatch(String user, JobItem jobItem) {
    try {
      return doProcessBatch(user, jobItem);
    } catch (ServiceUnavailableException e) {
      throw new RuntimeException("FetchTCG pricing lookup failed", e);
    } catch (ServiceException e) {
      setImportError(user, jobItem.getImportId(), e.getMessage());
      return new JobProcessor.FailureJobResult(e.getMessage());
    }
  }

  private JobProcessor.SuccessJobResult doProcessBatch(String user, JobItem jobItem)
      throws ServiceException {
    var importId = jobItem.getImportId();
    var continuation =
        jobItem.getContinuation() != null ? Integer.parseInt(jobItem.getContinuation()) : 0;

    var importKey =
        Key.builder()
            .partitionValue(ImportItem.formatPk(user))
            .sortValue(ImportItem.formatSk(importId))
            .build();
    var importItem = importTable.getItem(importKey);
    var totalRows = importItem.getRowCount() != null ? importItem.getRowCount() : 0;
    var pricingItem = pricingRepository.get(user);
    var priceFloor =
        pricingItem == null || pricingItem.getPriceFloor() == null
            ? Pricing.DEFAULT_PRICE_FLOOR
            : new BigDecimal(pricingItem.getPriceFloor());

    int batchEnd = Math.min(continuation + BATCH_SIZE, totalRows);
    int processed = continuation;

    var ctx = new RequestContext(user);
    var appraisalCatalog = AppraisalCatalogs.get(importItem.getGame());
    Map<String, AppraisalCatalog.ResolvedCard> batchCache = new HashMap<>();
    Map<String, GetCardResponse> cardCache = new HashMap<>();
    Map<String, BigDecimal> marketPriceCache = new HashMap<>();
    var sellerUsername = fetchTcgService.getAccount(ctx, new GetAccountRequest()).sellerUsername();

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
          appraiseRow(
              importItem.getGame(),
              appraisalCatalog,
              rowItem,
              priceFloor,
              sellerUsername,
              batchCache,
              cardCache,
              marketPriceCache,
              ctx);
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

    return new JobProcessor.SuccessJobResult(
        processed, complete ? null : Integer.toString(processed));
  }

  private void setImportError(String user, String importId, String error) {
    var importKey =
        Key.builder()
            .partitionValue(ImportItem.formatPk(user))
            .sortValue(ImportItem.formatSk(importId))
            .build();
    var importItem = importTable.getItem(importKey);
    importItem.setError(error);
    importItem.setUpdatedAt(clock.now());
    importTable.putItem(importItem);
  }

  private RowDecision appraiseRow(
      String game,
      AppraisalCatalog appraisalCatalog,
      ImportRowItem rowItem,
      BigDecimal priceFloor,
      String sellerUsername,
      Map<String, AppraisalCatalog.ResolvedCard> batchCache,
      Map<String, GetCardResponse> cardCache,
      Map<String, BigDecimal> marketPriceCache,
      RequestContext ctx)
      throws ServiceException {
    var identity = new CardIdentity(game, rowItem.getExternalId());
    var dedupeKey =
        identity.game()
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
      var resolution = appraisalCatalog.resolve(identity, rowItem, ctx, fetchTcgService, cardCache);
      if (resolution.reviewReason() != null) {
        return RowDecision.review(resolution.reviewReason());
      }
      cached = resolution.card();
      batchCache.put(dedupeKey, cached);
    }

    var marketPrice = marketPriceCache.get(cached.cardId());
    if (marketPrice == null) {
      var card = cardCache.get(cached.cardId());
      if (card == null) {
        card = fetchTcgService.getCard(ctx, new GetCardRequest(cached.cardId()));
        cardCache.put(cached.cardId(), card);
      }
      if (!cached.cardId().equals(card.id())) {
        throw new IllegalStateException("FetchTCG card identity did not match resolved card");
      }
      marketPrice = Pricing.marketPrice(card.marketPrice());
      marketPriceCache.put(cached.cardId(), marketPrice);
    }

    var competitors =
        competitorPricing.findCompetitorPriceTiers(
            ctx, cached.cardId(), Condition.valueOf(rowItem.getCondition()), sellerUsername);
    var suggestedPrice = pricingPolicy.calculatePrice(marketPrice, competitors, priceFloor);
    var result = appraisalPolicy.appraise(marketPrice, suggestedPrice);

    if (result.decision() == AppraisalPolicy.Decision.DISCARD) {
      return RowDecision.discard("below threshold", marketPrice.toPlainString());
    }

    return RowDecision.keep(
        marketPrice.toPlainString(),
        result.suggestedPrice().toPlainString(),
        cached.cardId(),
        cached.setId());
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
