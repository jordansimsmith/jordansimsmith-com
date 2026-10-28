package com.jordansimsmith.tcginventory.pricing.client;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import javax.annotation.Nullable;

public record UpdatePricingRequest(@JsonProperty("price_floor") @Nullable BigDecimal priceFloor) {}
