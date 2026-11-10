package com.jordansimsmith.tcginventory.publish.server;

import com.jordansimsmith.queue.QueueClient;
import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.service.ServiceException;
import com.jordansimsmith.tcginventory.inventory.client.FindSkusRequest;
import com.jordansimsmith.tcginventory.inventory.client.InventoryService;
import com.jordansimsmith.tcginventory.inventory.client.Sku;
import com.jordansimsmith.tcginventory.listings.client.ListingService;
import com.jordansimsmith.tcginventory.listings.client.UpdateListingRequest;
import com.jordansimsmith.tcginventory.orders.client.OrderService;
import com.jordansimsmith.tcginventory.orders.client.UpdateOrdersRequest;
import com.jordansimsmith.time.Clock;
import java.util.List;

public class PublishMessageHandler {
  private static final int BATCH_SIZE = 100;

  private final PublishRepository publishRepository;
  private final QueueClient<PublishMessage> publishQueue;
  private final Clock clock;
  private final OrderService orderService;
  private final InventoryService inventoryService;
  private final ListingService listingService;

  public PublishMessageHandler(
      PublishRepository publishRepository,
      QueueClient<PublishMessage> publishQueue,
      Clock clock,
      OrderService orderService,
      InventoryService inventoryService,
      ListingService listingService) {
    this.publishRepository = publishRepository;
    this.publishQueue = publishQueue;
    this.clock = clock;
    this.orderService = orderService;
    this.inventoryService = inventoryService;
    this.listingService = listingService;
  }

  public void handleMessage(RequestContext ctx, PublishMessage message) throws ServiceException {
    var publish = requirePublish(ctx.user());
    if (!publish.getPublishId().equals(message.publishId())
        || "succeeded".equals(publish.getStatus())) {
      return;
    }
    if ("queued".equals(publish.getStatus())) {
      publishRepository.startPublish(ctx.user(), message.publishId(), clock.now());
    }
    processPage(ctx, message, publish.getPublishedSkuCount());
  }

  private void processPage(RequestContext ctx, PublishMessage message, int previousCount)
      throws ServiceException {
    if (previousCount == 0) {
      orderService.updateOrders(ctx, new UpdateOrdersRequest());
    }

    var page =
        inventoryService.findSkus(ctx, new FindSkusRequest(null, null, true, BATCH_SIZE, null));
    processListings(ctx, message, previousCount, page.skus(), page.nextContinuation() != null);
  }

  private void processListings(
      RequestContext ctx,
      PublishMessage message,
      int previousCount,
      List<Sku> skus,
      boolean hasMore)
      throws ServiceException {
    for (var sku : skus) {
      listingService.updateListing(ctx, new UpdateListingRequest(sku.skuId()));
    }

    var publishedCount = previousCount + skus.size();
    if (!hasMore) {
      publishRepository.completePublish(
          ctx.user(), message.publishId(), previousCount, publishedCount, clock.now());
      return;
    }

    publishRepository.checkpointPublish(
        ctx.user(), message.publishId(), previousCount, publishedCount, clock.now());
    publishQueue.send(message, ctx.user(), message.publishId() + "#" + publishedCount);
  }

  private PublishItem requirePublish(String user) {
    var publish = publishRepository.get(user);
    if (publish == null
        || publish.getUser() == null
        || !publish.getUser().equals(user)
        || publish.getPublishId() == null
        || publish.getPublishId().isBlank()
        || publish.getStatus() == null
        || publish.getPublishedSkuCount() == null
        || publish.getPublishedSkuCount() < 0
        || publish.getStartedAt() == null
        || publish.getUpdatedAt() == null) {
      throw new IllegalStateException("Publish run metadata is missing or incomplete");
    }
    if (!List.of("queued", "running", "succeeded").contains(publish.getStatus())) {
      throw new IllegalStateException("Publish run has an unknown status");
    }
    if ("succeeded".equals(publish.getStatus()) != (publish.getFinishedAt() != null)) {
      throw new IllegalStateException("Publish run completion metadata is inconsistent");
    }
    if ("queued".equals(publish.getStatus()) && publish.getPublishedSkuCount() != 0) {
      throw new IllegalStateException("Queued Publish run has inconsistent progress metadata");
    }
    return publish;
  }
}
