package com.jordansimsmith.tcginventory.pricing.server;

import com.fasterxml.jackson.annotation.JsonProperty;

public record RepricingMessage(
    @JsonProperty("user") String user, @JsonProperty("repricing_id") String repricingId) {}
