package com.jordansimsmith.tcginventory.listings.client;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record FindListingsRequest(@JsonProperty("listing_ids") List<Integer> listingIds) {}
