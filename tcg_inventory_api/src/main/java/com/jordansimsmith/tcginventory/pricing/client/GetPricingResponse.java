package com.jordansimsmith.tcginventory.pricing.client;

import com.fasterxml.jackson.annotation.JsonProperty;

public record GetPricingResponse(@JsonProperty("price_floor") String priceFloor) {}
