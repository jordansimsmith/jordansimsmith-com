package com.jordansimsmith.tcginventory.pricing.client;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;

public record GetPriceResponse(
    @JsonProperty("market_price") BigDecimal marketPrice,
    @JsonProperty("suggested_price") BigDecimal suggestedPrice) {}
