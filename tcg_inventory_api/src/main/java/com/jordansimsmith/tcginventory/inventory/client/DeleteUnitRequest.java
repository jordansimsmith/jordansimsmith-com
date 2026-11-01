package com.jordansimsmith.tcginventory.inventory.client;

import com.fasterxml.jackson.annotation.JsonProperty;
import javax.annotation.Nullable;

public record DeleteUnitRequest(
    @JsonProperty("sku_id") String skuId,
    @JsonProperty("sequence_number") int sequenceNumber,
    @JsonProperty("reason") @Nullable String reason) {}
