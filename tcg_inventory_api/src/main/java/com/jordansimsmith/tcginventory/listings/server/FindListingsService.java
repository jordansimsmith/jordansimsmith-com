package com.jordansimsmith.tcginventory.listings.server;

import com.jordansimsmith.service.BadRequestException;
import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.service.ServiceException;
import com.jordansimsmith.tcginventory.listings.client.FindListingsRequest;
import com.jordansimsmith.tcginventory.listings.client.FindListingsResponse;
import com.jordansimsmith.tcginventory.listings.client.Listing;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;

public class FindListingsService {
  private final ListingRepository listingRepository;

  public FindListingsService(ListingRepository listingRepository) {
    this.listingRepository = listingRepository;
  }

  public FindListingsResponse findListings(RequestContext ctx, FindListingsRequest request)
      throws ServiceException {
    var listingIds = request.listingIds();
    if (listingIds == null
        || listingIds.size() > 100
        || listingIds.stream().anyMatch(listingId -> listingId == null)) {
      throw new BadRequestException("listing_ids must contain at most 100 nonnull values");
    }
    var uniqueListingIds = new LinkedHashSet<>(listingIds);
    if (uniqueListingIds.isEmpty()) {
      return new FindListingsResponse(new ArrayList<>());
    }

    var listingsById = new HashMap<Integer, Listing>();
    for (var item : listingRepository.findListings(ctx.user(), new ArrayList<>(uniqueListingIds))) {
      listingsById.put(item.getListingId(), new Listing(item.getListingId(), item.getSkuId()));
    }
    var listings = new ArrayList<Listing>(listingsById.size());
    for (var listingId : uniqueListingIds) {
      var listing = listingsById.get(listingId);
      if (listing != null) {
        listings.add(listing);
      }
    }
    return new FindListingsResponse(listings);
  }
}
