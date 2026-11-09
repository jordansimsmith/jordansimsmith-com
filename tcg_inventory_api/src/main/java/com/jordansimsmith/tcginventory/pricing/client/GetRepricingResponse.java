package com.jordansimsmith.tcginventory.pricing.client;

import com.fasterxml.jackson.annotation.JsonProperty;
import javax.annotation.Nullable;

public record GetRepricingResponse(
    @JsonProperty("status") RepricingStatus status,
    @JsonProperty("processed_sku_count") int processedSkuCount,
    @JsonProperty("started_at") long startedAt,
    @JsonProperty("finished_at") @Nullable Long finishedAt) {}
