package com.jordansimsmith.tcginventory.listings.client;

import com.jordansimsmith.service.BadRequestException;
import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.service.ServiceException;
import com.jordansimsmith.tcginventory.inventory.client.FindUnitsRequest;
import com.jordansimsmith.tcginventory.inventory.client.GetSkuRequest;
import com.jordansimsmith.tcginventory.inventory.client.InventoryService;
import com.jordansimsmith.tcginventory.inventory.client.UnitStatus;
import com.jordansimsmith.tcginventory.inventory.client.UpdateSkuRequest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;

public class FakeListingService implements ListingService {
  private final Map<Key, Listing> listings = new HashMap<>();

  private final InventoryService inventoryService;
  private final Map<SkuKey, Integer> currentListings = new HashMap<>();
  private int nextListingId = 1000000;

  public FakeListingService(InventoryService inventoryService) {
    this.inventoryService = inventoryService;
  }

  @Override
  public UpdateListingResponse updateListing(RequestContext ctx, UpdateListingRequest request)
      throws ServiceException {
    var sku = inventoryService.getSku(ctx, new GetSkuRequest(request.skuId())).sku();
    var key = new SkuKey(ctx.user(), sku.skuId());
    var inStock = false;
    String continuation = null;
    do {
      var response =
          inventoryService.findUnits(ctx, new FindUnitsRequest(sku.skuId(), 100, continuation));
      inStock |= response.units().stream().anyMatch(unit -> unit.status() == UnitStatus.IN_STOCK);
      continuation = response.nextContinuation();
    } while (continuation != null);
    if (inStock) {
      var listingId = currentListings.computeIfAbsent(key, ignored -> nextListingId++);
      listings.put(new Key(ctx.user(), listingId), new Listing(listingId, sku.skuId()));
    } else {
      currentListings.remove(key);
    }
    inventoryService.updateSku(ctx, new UpdateSkuRequest(sku.skuId(), null, false, sku.version()));
    return new UpdateListingResponse();
  }

  @Override
  public FindListingsResponse findListings(RequestContext ctx, FindListingsRequest request)
      throws ServiceException {
    var listingIds = request.listingIds();
    if (listingIds == null
        || listingIds.size() > 100
        || listingIds.stream().anyMatch(listingId -> listingId == null)) {
      throw new BadRequestException("listing_ids must contain at most 100 nonnull values");
    }
    var results = new ArrayList<Listing>();
    for (var listingId : new LinkedHashSet<>(listingIds)) {
      var listing = listings.get(new Key(ctx.user(), listingId));
      if (listing != null) {
        results.add(listing);
      }
    }
    return new FindListingsResponse(results);
  }

  public void seedListing(String user, Listing listing) {
    listings.put(new Key(user, listing.listingId()), listing);
    currentListings.put(new SkuKey(user, listing.skuId()), listing.listingId());
  }

  private record SkuKey(String user, String skuId) {}

  private record Key(String user, int listingId) {}
}
