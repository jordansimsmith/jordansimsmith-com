package com.jordansimsmith.tcginventory.inventory.client;

import com.fasterxml.jackson.annotation.JsonProperty;

public record CreateUnitResponse(
    @JsonProperty("sku_id") String skuId,
    @JsonProperty("sequence_number") int sequenceNumber,
    @JsonProperty("location") String location) {}
