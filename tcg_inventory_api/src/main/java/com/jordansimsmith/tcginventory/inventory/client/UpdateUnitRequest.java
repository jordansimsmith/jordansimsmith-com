package com.jordansimsmith.tcginventory.inventory.client;

import com.fasterxml.jackson.annotation.JsonProperty;
import javax.annotation.Nullable;

public record UpdateUnitRequest(
    @JsonProperty("sku_id") String skuId,
    @JsonProperty("sequence_number") int sequenceNumber,
    @JsonProperty("condition") @Nullable Condition condition) {}
