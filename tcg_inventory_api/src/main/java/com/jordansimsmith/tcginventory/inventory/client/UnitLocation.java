package com.jordansimsmith.tcginventory.inventory.client;

import com.fasterxml.jackson.annotation.JsonProperty;
import javax.annotation.Nullable;

public record UnitLocation(
    @JsonProperty("sku") Sku sku,
    @JsonProperty("sequence_number") int sequenceNumber,
    @JsonProperty("location") String location,
    @JsonProperty("current_location") String currentLocation,
    @JsonProperty("previous_card") @Nullable Sku previousCard,
    @JsonProperty("next_card") @Nullable Sku nextCard) {}
