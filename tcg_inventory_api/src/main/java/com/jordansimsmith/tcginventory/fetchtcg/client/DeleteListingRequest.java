package com.jordansimsmith.tcginventory.fetchtcg.client;

import com.fasterxml.jackson.annotation.JsonProperty;

public record DeleteListingRequest(@JsonProperty("listing_id") int listingId) {}
