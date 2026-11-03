package com.jordansimsmith.tcginventory.listings.client;

import com.jordansimsmith.service.BadRequestException;
import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.service.ServiceException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;

public class FakeListingService implements ListingService {
  private final Map<Key, Listing> listings = new HashMap<>();

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
  }

  private record Key(String user, int listingId) {}
}
