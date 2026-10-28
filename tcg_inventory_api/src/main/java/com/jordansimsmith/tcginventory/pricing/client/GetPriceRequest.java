package com.jordansimsmith.tcginventory.pricing.client;

import com.fasterxml.jackson.annotation.JsonProperty;

public record GetPriceRequest(
    @JsonProperty("fetchtcg_card_id") String fetchtcgCardId,
    @JsonProperty("condition") Condition condition) {}
