package com.jordansimsmith.tcginventory.pricing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jordansimsmith.dynamodb.Continuations;
import com.jordansimsmith.tcginventory.Condition;
import com.jordansimsmith.tcginventory.JobItem;
import com.jordansimsmith.tcginventory.JobProcessor;
import com.jordansimsmith.tcginventory.TcgInventoryFactory;
import com.jordansimsmith.tcginventory.TcgInventoryTable;
import com.jordansimsmith.tcginventory.fetchtcg.FetchTcgAuthException;
import com.jordansimsmith.tcginventory.fetchtcg.FetchTcgCardResolver;
import com.jordansimsmith.tcginventory.fetchtcg.FetchTcgClient;
import com.jordansimsmith.tcginventory.fetchtcg.FetchTcgTokenMinter;
import com.jordansimsmith.tcginventory.games.Games;
import com.jordansimsmith.tcginventory.inventory.InventoryRepository;
import com.jordansimsmith.tcginventory.inventory.SkuItem;
import com.jordansimsmith.tcginventory.inventory.UnitItem;
import com.jordansimsmith.time.Clock;
import com.jordansimsmith.ulid.UlidGenerator;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryEnhancedRequest;

public class RepriceJobProcessor implements JobProcessor {
  public static final int BATCH_SIZE = 100;

  private final DynamoDbTable<SkuItem> skuTable;
  private final InventoryRepository inventoryRepository;
  private final ObjectMapper objectMapper;
  private final FetchTcgClient fetchTcgClient;
  private final FetchTcgTokenMinter fetchTcgTokenMinter;
  private final CompetitorPricing competitorPricing;
  private final PricingPolicy pricingPolicy;
  private final PricingRepository pricingRepository;
  private final Clock clock;
  private final UlidGenerator ulidGenerator;

  public RepriceJobProcessor(TcgInventoryFactory factory) {
    var enhancedClient = factory.dynamoDbEnhancedClient();
    this.clock = factory.clock();
    this.ulidGenerator = factory.ulidGenerator();
    this.skuTable = TcgInventoryTable.table(enhancedClient, SkuItem.class);
    this.inventoryRepository =
        new InventoryRepository(
            TcgInventoryTable.table(enhancedClient, UnitItem.class),
            factory.dynamoDbClient(),
            clock,
            ulidGenerator);
    this.objectMapper = factory.objectMapper();
    this.fetchTcgClient = factory.fetchTcgClient();
    this.fetchTcgTokenMinter = factory.fetchTcgTokenMinter();
    this.competitorPricing = new CompetitorPricing(fetchTcgClient);
    this.pricingPolicy = new PricingPolicy();
    this.pricingRepository = new PricingRepository(factory);
  }

  @Override
  public JobResult processBatch(String user, JobItem jobItem) {
    try {
      return doProcessBatch(user, jobItem);
    } catch (FetchTcgAuthException e) {
      return new FailureJobResult(FetchTcgAuthException.USER_MESSAGE);
    } catch (UnpriceableSkuException e) {
      return new FailureJobResult(e.getMessage());
    }
  }

  private SuccessJobResult doProcessBatch(String user, JobItem jobItem) {
    var jobId = requiredValue(jobItem.getJobId(), "job_id");
    var previousProcessedCount = requiredCount(jobItem.getProcessedCount(), "processed_count");
    var requestBuilder =
        QueryEnhancedRequest.builder()
            .queryConditional(
                QueryConditional.keyEqualTo(
                    Key.builder().partitionValue(SkuItem.formatGsi2pk(user)).build()))
            .limit(BATCH_SIZE);
    if (jobItem.getContinuation() != null) {
      requestBuilder.exclusiveStartKey(
          Continuations.decode(jobItem.getContinuation(), objectMapper));
    }

    var page =
        skuTable.index(TcgInventoryTable.GSI2_NAME).query(requestBuilder.build()).stream()
            .findFirst()
            .orElse(null);
    if (page == null || page.items().isEmpty()) {
      return new SuccessJobResult(previousProcessedCount, null);
    }
    var candidates = page.items();
    var priceFloor = currentPriceFloor(user);

    var profile = fetchTcgClient.getProfile(fetchTcgTokenMinter.mint(user));
    var sellerUsername = profile == null ? null : profile.username();
    if (sellerUsername == null || sellerUsername.isBlank()) {
      throw new UnpriceableSkuException("FetchTCG profile did not include a username");
    }

    var cardCache = new HashMap<String, FetchTcgClient.GetCardResponse>();
    var competitorCache = new HashMap<CompetitorKey, List<CompetitorPricing.CompetitorPriceTier>>();
    for (var candidate : candidates) {
      var skuId = candidate.getSkuId();
      if (skuId == null || skuId.isBlank()) {
        throw new UnpriceableSkuException("SKU query returned an item without sku_id");
      }
      var sku = getSku(user, skuId);
      if (sku == null) {
        throw new UnpriceableSkuException("SKU not found: " + skuId);
      }
      var game = requireGame(sku);
      var condition = requireCondition(sku);
      if (!game.supportsFinish(sku.getFinish())) {
        throw new UnpriceableSkuException("unsupported finish for SKU " + skuId);
      }
      var version = sku.getVersion();
      if (version == null) {
        throw new UnpriceableSkuException("SKU has no version: " + skuId);
      }

      var inStock =
          inventoryRepository.findUnits(user, skuId).stream()
              .anyMatch(unit -> "in_stock".equals(unit.getStatus()));
      if (!inStock) {
        continue;
      }

      var beforePrice = sku.getSuggestedPrice();
      if (beforePrice == null || beforePrice.isBlank()) {
        throw new UnpriceableSkuException("SKU has no current suggested price: " + skuId);
      }
      parseBeforePrice(skuId, beforePrice);
      var cardId = sku.getFetchtcgCardId();
      if (cardId == null || cardId.isBlank()) {
        throw new UnpriceableSkuException("SKU has no FetchTCG card ID: " + skuId);
      }
      var card = cardCache.computeIfAbsent(cardId, fetchTcgClient::getCard);
      if (card == null || !cardId.equals(card.id())) {
        throw new UnpriceableSkuException("FetchTCG card identity did not match SKU " + skuId);
      }
      var marketPrice = FetchTcgCardResolver.marketPrice(card);
      var key = new CompetitorKey(cardId, condition);
      var competitors =
          competitorCache.computeIfAbsent(
              key,
              ignored ->
                  competitorPricing.findCompetitorPriceTiers(cardId, condition, sellerUsername));
      var afterPrice = pricingPolicy.calculatePrice(marketPrice, competitors, priceFloor);

      inventoryRepository.updateSkuPrice(
          user,
          skuId,
          version,
          jobId,
          beforePrice,
          afterPrice.toPlainString(),
          ulidGenerator.generate(),
          clock.now());
    }

    var continuation = Continuations.encode(page.lastEvaluatedKey(), objectMapper);
    return new SuccessJobResult(previousProcessedCount + candidates.size(), continuation);
  }

  private BigDecimal currentPriceFloor(String user) {
    var pricingItem = pricingRepository.get(user);
    if (pricingItem == null || pricingItem.getPriceFloor() == null) {
      return Pricing.DEFAULT_PRICE_FLOOR;
    }
    try {
      var priceFloor = new BigDecimal(pricingItem.getPriceFloor());
      if (priceFloor.signum() < 0) {
        throw new UnpriceableSkuException("saved price floor is invalid");
      }
      return priceFloor.setScale(2);
    } catch (NumberFormatException | ArithmeticException e) {
      throw new UnpriceableSkuException("saved price floor is invalid");
    }
  }

  private SkuItem getSku(String user, String skuId) {
    var key =
        Key.builder()
            .partitionValue(SkuItem.formatPk(user, skuId))
            .sortValue(SkuItem.formatSk())
            .build();
    return skuTable.getItem(request -> request.key(key).consistentRead(true));
  }

  private Games.Game requireGame(SkuItem sku) {
    try {
      return Games.get(sku.getGame());
    } catch (IllegalArgumentException e) {
      throw new UnpriceableSkuException("unsupported game for SKU " + sku.getSkuId());
    }
  }

  private Condition requireCondition(SkuItem sku) {
    try {
      return Condition.valueOf(sku.getCondition());
    } catch (IllegalArgumentException | NullPointerException e) {
      throw new UnpriceableSkuException("unsupported condition for SKU " + sku.getSkuId());
    }
  }

  private void parseBeforePrice(String skuId, String value) {
    try {
      if (new BigDecimal(value).signum() < 0) {
        throw new UnpriceableSkuException("SKU has an invalid current suggested price: " + skuId);
      }
    } catch (NumberFormatException e) {
      throw new UnpriceableSkuException("SKU has an invalid current suggested price: " + skuId);
    }
  }

  private int requiredCount(Integer value, String attribute) {
    if (value == null || value < 0) {
      throw new UnpriceableSkuException("reprice job has an invalid " + attribute);
    }
    return value;
  }

  private String requiredValue(String value, String attribute) {
    if (value == null || value.isBlank()) {
      throw new UnpriceableSkuException("reprice job has no " + attribute);
    }
    return value;
  }

  private record CompetitorKey(String cardId, Condition condition) {}

  private static class UnpriceableSkuException extends RuntimeException {
    UnpriceableSkuException(String message) {
      super(message);
    }
  }
}
