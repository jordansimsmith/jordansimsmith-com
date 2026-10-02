package com.jordansimsmith.pricetracker;

import com.fasterxml.jackson.annotation.JsonProperty;

public record JobMessage(
    @JsonProperty("job_type") String jobType,
    @JsonProperty("product_id") String productId,
    @JsonProperty("scheduled_at") String scheduledAt) {}
