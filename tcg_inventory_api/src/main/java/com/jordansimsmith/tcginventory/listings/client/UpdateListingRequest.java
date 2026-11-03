package com.jordansimsmith.tcginventory.listings.client;

import com.fasterxml.jackson.annotation.JsonProperty;

public record UpdateListingRequest(@JsonProperty("sku_id") String skuId) {}
