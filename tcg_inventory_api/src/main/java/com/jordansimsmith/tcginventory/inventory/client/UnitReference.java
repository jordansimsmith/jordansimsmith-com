package com.jordansimsmith.tcginventory.inventory.client;

import com.fasterxml.jackson.annotation.JsonProperty;

public record UnitReference(
    @JsonProperty("sku_id") String skuId, @JsonProperty("sequence_number") int sequenceNumber) {}
