package com.jordansimsmith.tcginventory.pricing;

import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.service.ServiceException;
import com.jordansimsmith.service.ServiceUnavailableException;
import com.jordansimsmith.tcginventory.JobItem;
import com.jordansimsmith.tcginventory.JobProcessor;
import com.jordansimsmith.tcginventory.games.client.Game;
import com.jordansimsmith.tcginventory.games.client.GamesService;
import com.jordansimsmith.tcginventory.games.client.GetGameRequest;
import com.jordansimsmith.tcginventory.inventory.client.FindSkusRequest;
import com.jordansimsmith.tcginventory.inventory.client.FindUnitsRequest;
import com.jordansimsmith.tcginventory.inventory.client.GetSkuRequest;
import com.jordansimsmith.tcginventory.inventory.client.InventoryService;
import com.jordansimsmith.tcginventory.inventory.client.Sku;
import com.jordansimsmith.tcginventory.inventory.client.Unit;
import com.jordansimsmith.tcginventory.inventory.client.UnitStatus;
import com.jordansimsmith.tcginventory.inventory.client.UpdateSkuRequest;
import com.jordansimsmith.tcginventory.pricing.client.Condition;
import com.jordansimsmith.tcginventory.pricing.client.GetPriceRequest;
import com.jordansimsmith.tcginventory.pricing.client.PricingService;
import java.util.ArrayList;
import java.util.HashMap;

public class RepriceJobProcessor implements JobProcessor {
  public static final int BATCH_SIZE = 100;

  private final InventoryService inventoryService;
  private final PricingService pricingService;
  private final GamesService gamesService;

  public RepriceJobProcessor(
      InventoryService inventoryService, PricingService pricingService, GamesService gamesService) {
    this.gamesService = gamesService;
    this.inventoryService = inventoryService;
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
    var previousProcessedCount = requiredCount(jobItem.getProcessedCount(), "processed_count");
    var ctx = new RequestContext(user);
    var response =
        inventoryService.findSkus(
            ctx, new FindSkusRequest(null, null, null, BATCH_SIZE, jobItem.getContinuation()));
    if (response.skus().isEmpty()) {
      return new SuccessJobResult(previousProcessedCount, response.nextContinuation());
    }
    var candidates = response.skus();
    var gamesById = new HashMap<String, Game>();
    for (var candidate : candidates) {
      var skuId = candidate.skuId();
      var sku = inventoryService.getSku(ctx, new GetSkuRequest(skuId)).sku();
      var game = gamesById.get(sku.game());
      if (game == null) {
        game = gamesService.getGame(ctx, new GetGameRequest(sku.game())).game();
        gamesById.put(sku.game(), game);
      }
      var condition = requireCondition(sku);
      if (game.finishes().stream().noneMatch(finish -> finish.id().equals(sku.finish()))) {
        throw new UnpriceableSkuException("unsupported finish for SKU " + skuId);
      }

      var units = new ArrayList<Unit>();
      String unitContinuation = null;
      do {
        var unitResponse =
            inventoryService.findUnits(ctx, new FindUnitsRequest(skuId, 100, unitContinuation));
        units.addAll(unitResponse.units());
        unitContinuation = unitResponse.nextContinuation();
      } while (unitContinuation != null);
      var inStock = units.stream().anyMatch(unit -> unit.status() == UnitStatus.IN_STOCK);
      if (!inStock) {
        continue;
      }

      var cardId = sku.fetchtcgCardId();
      if (cardId == null || cardId.isBlank()) {
        throw new UnpriceableSkuException("SKU has no FetchTCG card ID: " + skuId);
      }
      var price = pricingService.getPrice(ctx, new GetPriceRequest(cardId, condition));
      inventoryService.updateSku(
          ctx, new UpdateSkuRequest(skuId, price.suggestedPrice(), null, null));
    }

    return new SuccessJobResult(
        previousProcessedCount + candidates.size(), response.nextContinuation());
  }

  private Condition requireCondition(Sku sku) {
    if (sku.condition() == null) {
      throw new UnpriceableSkuException("unsupported condition for SKU " + sku.skuId());
    }
    return Condition.valueOf(sku.condition().name());
  }

  private int requiredCount(Integer value, String attribute) {
    if (value == null || value < 0) {
      throw new UnpriceableSkuException("reprice job has an invalid " + attribute);
    }
    return value;
  }

  private static class UnpriceableSkuException extends RuntimeException {
    UnpriceableSkuException(String message) {
      super(message);
    }
  }
}
