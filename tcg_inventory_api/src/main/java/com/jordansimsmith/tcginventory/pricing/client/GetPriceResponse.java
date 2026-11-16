package com.jordansimsmith.tcginventory.pricing.client;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import javax.annotation.Nullable;

public record GetPriceResponse(
    @JsonProperty("market_price") @Nullable BigDecimal marketPrice,
    @JsonProperty("suggested_price") @Nullable BigDecimal suggestedPrice) {}
