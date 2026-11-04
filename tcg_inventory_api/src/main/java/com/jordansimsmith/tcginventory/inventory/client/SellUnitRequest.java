package com.jordansimsmith.tcginventory.inventory.client;

import com.fasterxml.jackson.annotation.JsonProperty;

public record SellUnitRequest(
    @JsonProperty("sku_id") String skuId,
    @JsonProperty("sequence_number") int sequenceNumber,
    @JsonProperty("reservation_id") String reservationId) {}
