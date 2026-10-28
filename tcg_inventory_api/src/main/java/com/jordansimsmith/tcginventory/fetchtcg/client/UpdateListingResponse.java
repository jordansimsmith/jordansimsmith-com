package com.jordansimsmith.tcginventory.fetchtcg.client;

import com.fasterxml.jackson.annotation.JsonProperty;

public record UpdateListingResponse(
    @JsonProperty("listing_id") int listingId,
    @JsonProperty("remaining_quantity") int remainingQuantity) {}
