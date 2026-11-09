package com.jordansimsmith.tcginventory.pricing.server;

import com.jordansimsmith.queue.QueueClient;
import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.service.ServiceException;
import com.jordansimsmith.tcginventory.inventory.client.Condition;
import com.jordansimsmith.tcginventory.inventory.client.FindSkusRequest;
import com.jordansimsmith.tcginventory.inventory.client.FindUnitsRequest;
import com.jordansimsmith.tcginventory.inventory.client.GetSkuRequest;
import com.jordansimsmith.tcginventory.inventory.client.InventoryService;
import com.jordansimsmith.tcginventory.inventory.client.Sku;
import com.jordansimsmith.tcginventory.inventory.client.Unit;
import com.jordansimsmith.tcginventory.inventory.client.UnitStatus;
import com.jordansimsmith.tcginventory.inventory.client.UpdateSkuRequest;
import com.jordansimsmith.tcginventory.pricing.client.GetPriceRequest;
import com.jordansimsmith.tcginventory.pricing.client.PricingService;
import com.jordansimsmith.time.Clock;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RepricingMessageHandler {
  private static final Logger LOGGER = LoggerFactory.getLogger(RepricingMessageHandler.class);
  private static final int BATCH_SIZE = 100;

  private final RepricingRepository repricingRepository;
  private final QueueClient<RepricingMessage> pricingQueue;
  private final Clock clock;
  private final InventoryService inventoryService;
  private final PricingService pricingService;

  public RepricingMessageHandler(
      RepricingRepository repricingRepository,
      QueueClient<RepricingMessage> pricingQueue,
      Clock clock,
      InventoryService inventoryService,
      PricingService pricingService) {
    this.repricingRepository = repricingRepository;
    this.pricingQueue = pricingQueue;
    this.clock = clock;
    this.inventoryService = inventoryService;
    this.pricingService = pricingService;
  }

  public void handleMessage(RequestContext ctx, RepricingMessage message) throws ServiceException {
    LOGGER.info("starting repricing {} for user {}", message.repricingId(), ctx.user());
    var run = requireRun(ctx.user());
    if (!message.repricingId().equals(run.getRepricingId())) {
      return;
    }
    if ("succeeded".equals(run.getStatus())) {
      return;
    }
    if ("queued".equals(run.getStatus())) {
      repricingRepository.startRepricing(ctx.user(), message.repricingId(), clock.now());
    }

    var response =
        inventoryService.findSkus(
            ctx, new FindSkusRequest(null, null, null, BATCH_SIZE, run.getContinuation()));
    processSkus(ctx, response.skus());

    var previousCount = run.getProcessedSkuCount();
    var processedCount = previousCount + response.skus().size();
    if (response.nextContinuation() == null) {
      repricingRepository.completeRepricing(
          ctx.user(), message.repricingId(), previousCount, processedCount, clock.now());
      LOGGER.info("completed repricing {} for user {}", message.repricingId(), ctx.user());
      return;
    }

    repricingRepository.checkpointRepricing(
        ctx.user(),
        message.repricingId(),
        previousCount,
        processedCount,
        response.nextContinuation(),
        clock.now());
    pricingQueue.send(message, message.repricingId(), message.repricingId() + "#" + processedCount);
  }

  private RepricingItem requireRun(String user) {
    var run = repricingRepository.get(user);
    if (run == null
        || run.getUser() == null
        || !run.getUser().equals(user)
        || run.getRepricingId() == null
        || run.getRepricingId().isBlank()
        || run.getStatus() == null
        || run.getProcessedSkuCount() == null
        || run.getProcessedSkuCount() < 0
        || run.getStartedAt() == null
        || run.getUpdatedAt() == null) {
      throw new IllegalStateException("Repricing run metadata is missing or incomplete");
    }
    if (!List.of("queued", "running", "succeeded").contains(run.getStatus())) {
      throw new IllegalStateException("Repricing run has an unknown status");
    }
    if ("succeeded".equals(run.getStatus()) != (run.getFinishedAt() != null)) {
      throw new IllegalStateException("Repricing run completion metadata is inconsistent");
    }
    if (!"running".equals(run.getStatus()) && run.getContinuation() != null) {
      throw new IllegalStateException("Repricing run continuation metadata is inconsistent");
    }
    if ("queued".equals(run.getStatus()) && run.getProcessedSkuCount() != 0) {
      throw new IllegalStateException("Queued repricing run has inconsistent progress metadata");
    }
    return run;
  }

  private void processSkus(RequestContext ctx, List<Sku> candidates) throws ServiceException {
    for (var candidate : candidates) {
      processSku(ctx, candidate);
    }
  }

  private void processSku(RequestContext ctx, Sku candidate) throws ServiceException {
    var skuId = candidate.skuId();
    var sku = inventoryService.getSku(ctx, new GetSkuRequest(skuId)).sku();
    var condition = requireCondition(sku);

    var units = collectUnits(ctx, skuId);
    if (units.stream().noneMatch(unit -> unit.status() == UnitStatus.IN_STOCK)) {
      return;
    }

    var cardId = sku.fetchtcgCardId();
    if (cardId == null || cardId.isBlank()) {
      throw new IllegalStateException("SKU has no FetchTCG card ID: " + skuId);
    }
    var price = pricingService.getPrice(ctx, new GetPriceRequest(cardId, condition));
    inventoryService.updateSku(
        ctx, new UpdateSkuRequest(skuId, price.suggestedPrice(), null, null));
  }

  private List<Unit> collectUnits(RequestContext ctx, String skuId) throws ServiceException {
    var units = new ArrayList<Unit>();
    String continuation = null;
    do {
      var response =
          inventoryService.findUnits(ctx, new FindUnitsRequest(skuId, BATCH_SIZE, continuation));
      units.addAll(response.units());
      continuation = response.nextContinuation();
    } while (continuation != null);
    return units;
  }

  private Condition requireCondition(Sku sku) {
    if (sku.condition() == null) {
      throw new IllegalStateException("unsupported condition for SKU " + sku.skuId());
    }
    return sku.condition();
  }
}
