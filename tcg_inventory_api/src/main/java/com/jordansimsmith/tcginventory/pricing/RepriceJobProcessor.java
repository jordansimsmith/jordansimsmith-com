package com.jordansimsmith.tcginventory.pricing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jordansimsmith.dynamodb.Continuations;
import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.service.ServiceException;
import com.jordansimsmith.service.ServiceUnavailableException;
import com.jordansimsmith.tcginventory.JobItem;
import com.jordansimsmith.tcginventory.JobProcessor;
import com.jordansimsmith.tcginventory.TcgInventoryTable;
import com.jordansimsmith.tcginventory.games.client.Game;
import com.jordansimsmith.tcginventory.games.client.GamesService;
import com.jordansimsmith.tcginventory.games.client.GetGameRequest;
import com.jordansimsmith.tcginventory.inventory.InventoryRepository;
import com.jordansimsmith.tcginventory.inventory.SkuItem;
import com.jordansimsmith.tcginventory.pricing.client.Condition;
import com.jordansimsmith.tcginventory.pricing.client.GetPriceRequest;
import com.jordansimsmith.tcginventory.pricing.client.PricingService;
import com.jordansimsmith.time.Clock;
import com.jordansimsmith.ulid.UlidGenerator;
import java.math.BigDecimal;
import java.util.HashMap;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryEnhancedRequest;

public class RepriceJobProcessor implements JobProcessor {
  public static final int BATCH_SIZE = 100;

  private final DynamoDbTable<SkuItem> skuTable;
  private final InventoryRepository inventoryRepository;
  private final ObjectMapper objectMapper;
  private final PricingService pricingService;
  private final Clock clock;
  private final UlidGenerator ulidGenerator;
  private final GamesService gamesService;

  public RepriceJobProcessor(
      DynamoDbTable<SkuItem> skuTable,
      InventoryRepository inventoryRepository,
      ObjectMapper objectMapper,
      PricingService pricingService,
      Clock clock,
      UlidGenerator ulidGenerator,
      GamesService gamesService) {
    this.clock = clock;
    this.ulidGenerator = ulidGenerator;
    this.gamesService = gamesService;
    this.skuTable = skuTable;
    this.inventoryRepository = inventoryRepository;
    this.objectMapper = objectMapper;
    this.pricingService = pricingService;
  }

  @Override
  public JobResult processBatch(String user, JobItem jobItem) {
    try {
      return doProcessBatch(user, jobItem);
    } catch (ServiceUnavailableException e) {
      throw new RuntimeException("pricing lookup failed", e);
    } catch (ServiceException e) {
      return new FailureJobResult(e.getMessage());
    } catch (UnpriceableSkuException e) {
      return new FailureJobResult(e.getMessage());
    }
  }

  private SuccessJobResult doProcessBatch(String user, JobItem jobItem) throws ServiceException {
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
    var ctx = new RequestContext(user);
    var gamesById = new HashMap<String, Game>();
    for (var candidate : candidates) {
      var skuId = candidate.getSkuId();
      if (skuId == null || skuId.isBlank()) {
        throw new UnpriceableSkuException("SKU query returned an item without sku_id");
      }
      var sku = getSku(user, skuId);
      if (sku == null) {
        throw new UnpriceableSkuException("SKU not found: " + skuId);
      }
      var game = gamesById.get(sku.getGame());
      if (game == null) {
        game = gamesService.getGame(ctx, new GetGameRequest(sku.getGame())).game();
        gamesById.put(sku.getGame(), game);
      }
      var condition = requireCondition(sku);
      if (game.finishes().stream().noneMatch(finish -> finish.id().equals(sku.getFinish()))) {
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
      var price = pricingService.getPrice(ctx, new GetPriceRequest(cardId, condition));

      inventoryRepository.updateSkuPrice(
          user,
          skuId,
          version,
          jobId,
          beforePrice,
          price.suggestedPrice().toPlainString(),
          ulidGenerator.generate(),
          clock.now());
    }

    var continuation = Continuations.encode(page.lastEvaluatedKey(), objectMapper);
    return new SuccessJobResult(previousProcessedCount + candidates.size(), continuation);
  }

  private SkuItem getSku(String user, String skuId) {
    var key =
        Key.builder()
            .partitionValue(SkuItem.formatPk(user, skuId))
            .sortValue(SkuItem.formatSk())
            .build();
    return skuTable.getItem(request -> request.key(key).consistentRead(true));
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

  private static class UnpriceableSkuException extends RuntimeException {
    UnpriceableSkuException(String message) {
      super(message);
    }
  }
}
