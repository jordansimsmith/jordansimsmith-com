package com.jordansimsmith.tcginventory.publish.server;

import com.jordansimsmith.service.NotFoundException;
import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.service.ServiceException;
import com.jordansimsmith.tcginventory.inventory.client.FindSkusRequest;
import com.jordansimsmith.tcginventory.inventory.client.InventoryService;
import com.jordansimsmith.tcginventory.publish.client.GetPublishRequest;
import com.jordansimsmith.tcginventory.publish.client.GetPublishResponse;
import com.jordansimsmith.tcginventory.publish.client.PublishStatus;

public class GetPublishService {
  private static final int PAGE_SIZE = 100;

  private final PublishRepository publishRepository;
  private final InventoryService inventoryService;

  public GetPublishService(PublishRepository publishRepository, InventoryService inventoryService) {
    this.publishRepository = publishRepository;
    this.inventoryService = inventoryService;
  }

  public GetPublishResponse getPublish(RequestContext ctx, GetPublishRequest request)
      throws ServiceException {
    var publish = publishRepository.get(ctx.user());
    if (publish == null) {
      throw new NotFoundException("Not Found");
    }

    var status =
        switch (publish.getStatus()) {
          case "queued" -> PublishStatus.QUEUED;
          case "running" -> PublishStatus.RUNNING;
          case "succeeded" -> PublishStatus.SUCCEEDED;
          default -> throw new IllegalStateException("Publish run has an unknown status");
        };

    var pendingSkuCount = countPendingSkus(ctx);
    return new GetPublishResponse(
        status,
        publish.getPublishedSkuCount(),
        publish.getStartedAt().getEpochSecond(),
        publish.getFinishedAt() == null ? null : publish.getFinishedAt().getEpochSecond(),
        pendingSkuCount);
  }

  private int countPendingSkus(RequestContext ctx) throws ServiceException {
    var pendingSkuCount = 0;
    String continuation = null;
    do {
      var response =
          inventoryService.findSkus(
              ctx, new FindSkusRequest(null, null, true, PAGE_SIZE, continuation));
      pendingSkuCount += response.skus().size();
      continuation = response.nextContinuation();
    } while (continuation != null);
    return pendingSkuCount;
  }
}
