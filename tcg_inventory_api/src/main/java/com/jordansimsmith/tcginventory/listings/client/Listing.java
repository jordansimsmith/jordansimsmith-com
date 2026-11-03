package com.jordansimsmith.tcginventory.listings.client;

import com.fasterxml.jackson.annotation.JsonProperty;

public record Listing(
    @JsonProperty("listing_id") int listingId, @JsonProperty("sku_id") String skuId) {}
