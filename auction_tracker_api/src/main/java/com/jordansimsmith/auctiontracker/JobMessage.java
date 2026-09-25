package com.jordansimsmith.auctiontracker;

import com.fasterxml.jackson.annotation.JsonProperty;

public record JobMessage(
    @JsonProperty("job_type") String jobType,
    @JsonProperty("search_id") String searchId,
    @JsonProperty("scheduled_at") String scheduledAt) {}
