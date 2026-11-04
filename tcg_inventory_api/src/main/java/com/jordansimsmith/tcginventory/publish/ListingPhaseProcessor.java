package com.jordansimsmith.tcginventory.publish;

import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.service.ServiceException;
import com.jordansimsmith.tcginventory.JobProcessor;
import com.jordansimsmith.tcginventory.inventory.client.FindSkusRequest;
import com.jordansimsmith.tcginventory.inventory.client.InventoryService;
import com.jordansimsmith.tcginventory.listings.client.ListingService;
import com.jordansimsmith.tcginventory.listings.client.UpdateListingRequest;

public class ListingPhaseProcessor {
  public static final int BATCH_SIZE = 100;
  private final InventoryService inventoryService;
  private final ListingService listingService;

  public ListingPhaseProcessor(InventoryService inventoryService, ListingService listingService) {
    this.inventoryService = inventoryService;
    this.listingService = listingService;
  }

  public JobProcessor.SuccessJobResult process(String user, int continuation)
      throws ServiceException {
    var ctx = new RequestContext(user);
    var response =
        inventoryService.findSkus(ctx, new FindSkusRequest(null, null, true, BATCH_SIZE, null));

    int processed = 0;
    for (var sku : response.skus()) {
      listingService.updateListing(ctx, new UpdateListingRequest(sku.skuId()));
      processed++;
    }
    return new JobProcessor.SuccessJobResult(
        continuation + processed,
        response.nextContinuation() == null ? null : Integer.toString(continuation + processed));
  }
}
