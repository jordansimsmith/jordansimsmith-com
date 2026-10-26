package com.jordansimsmith.tcginventory.fetchtcg.client;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.util.Map;
import javax.annotation.Nullable;

public record GetCardResponse(
    @JsonProperty("id") String id,
    @JsonProperty("name") String name,
    @JsonProperty("market_price") @Nullable BigDecimal marketPrice,
    @JsonProperty("external_references") Map<String, String> externalReferences) {}
